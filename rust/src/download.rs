use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::http;
use crate::model::{DownloadProgress, DownloadRequest, LibraryItem};
use crate::util::{
    extension_of, is_document_ext, mime_for, now_ms, origin, percent_decode, sanitize_filename,
};

pub struct Task {
    pub id: String,
    pub title: String,
    pub authors: Vec<String>,
    pub format: String,
    pub url: String,
    pub cover: Option<String>,
    pub book_url: Option<String>,
    pub filename: String,
    pub state: String,
    pub received: u64,
    pub total: u64,
    pub speed: u64,
    pub temp_path: String,
    pub error: Option<String>,
    pub created_at: i64,
    pub cancelled: Arc<AtomicBool>,
}

impl Task {
    pub fn progress(&self) -> DownloadProgress {
        DownloadProgress {
            id: self.id.clone(),
            title: self.title.clone(),
            authors: self.authors.clone(),
            state: self.state.clone(),
            received: self.received,
            total: self.total,
            speed: self.speed,
            format: self.format.clone(),
            filename: self.filename.clone(),
            temp_path: if self.state == "saving" {
                Some(self.temp_path.clone())
            } else {
                None
            },
            error: self.error.clone(),
            created_at: self.created_at,
        }
    }
}

fn filename_for(title: &str, authors: &[String], ext: &str) -> String {
    let base = if title.trim().is_empty() {
        "Untitled".to_string()
    } else if let Some(author) = authors.first().filter(|a| !a.trim().is_empty()) {
        format!("{} - {}", title.trim(), author.trim())
    } else {
        title.trim().to_string()
    };
    let safe = sanitize_filename(&base);
    if ext.is_empty() {
        safe
    } else {
        format!("{}.{}", safe, ext)
    }
}

fn temp_name(id: &str) -> String {
    let safe: String = id
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect();
    format!("{}.part", safe)
}

fn header(resp: &ureq::Response, name: &str) -> Option<String> {
    resp.header(name).map(|s| s.to_string())
}

fn parse_total(resp: &ureq::Response, existing: u64) -> u64 {
    if let Some(range) = header(resp, "content-range") {
        if let Some(total) = range.rsplit('/').next() {
            if let Ok(t) = total.trim().parse::<u64>() {
                return t;
            }
        }
    }
    let len = header(resp, "content-length")
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);
    if len == 0 {
        0
    } else if resp.status() == 206 {
        existing + len
    } else {
        len
    }
}

fn filename_from_disposition(resp: &ureq::Response) -> Option<String> {
    let raw = header(resp, "content-disposition")?;
    let lower = raw.to_lowercase();
    if let Some(idx) = lower.find("filename*=") {
        let tail = &raw[idx + 10..];
        let value = tail.split(';').next().unwrap_or(tail).trim().trim_matches('"');
        let decoded = match value.find("''") {
            Some(pos) => percent_decode(&value[pos + 2..]),
            None => value.to_string(),
        };
        let cleaned = sanitize_filename(&decoded);
        if !cleaned.is_empty() {
            return Some(cleaned);
        }
    }
    if let Some(idx) = lower.find("filename=") {
        let tail = &raw[idx + 9..];
        let value = tail.split(';').next().unwrap_or(tail).trim().trim_matches('"');
        let cleaned = sanitize_filename(&percent_decode(value));
        if !cleaned.is_empty() {
            return Some(cleaned);
        }
    }
    None
}

fn title_from_url(url: &str) -> String {
    let path = url.split(['?', '#']).next().unwrap_or(url);
    let last = path.rsplit('/').next().unwrap_or("");
    let decoded = percent_decode(last);
    if let Some(dot) = decoded.rfind('.') {
        decoded[..dot].replace(['_', '-'], " ").trim().to_string()
    } else {
        decoded.replace(['_', '-'], " ").trim().to_string()
    }
}

/// Anna's Archive slow downloads: /slow_download/<md5>/<path>/<server> first
/// serves a waitlist page ("let waitSeconds = N;"), then re-serving the same
/// URL renders the partner page with the final file link. Returns the file URL.
fn resolve_slow_download(
    task: &Arc<Mutex<Task>>,
    url: &str,
    cancelled: &Arc<AtomicBool>,
) -> Result<String, String> {
    let base_origin = origin(url);
    let mut attempt_url = url.to_string();

    for _attempt in 0..8 {
        if cancelled.load(Ordering::Relaxed) {
            return Err("cancelled".to_string());
        }
        let html = http::get_text(&attempt_url)?;
        if crate::is_challenge_page(&html) {
            return Err("Blocked by the site protection. Open the app again to refresh the session.".to_string());
        }

        // Direct file link page: <p class="mb-4 text-xl font-bold"> ... <a href="URL">
        if let Some(pos) = html.find("mb-4 text-xl font-bold") {
            let window_end = (pos + 3000).min(html.len());
            let tail = &html[pos..window_end];
            if let Some(hpos) = tail.find("href=\"") {
                let rest = &tail[hpos + 6..];
                if let Some(end) = rest.find('"') {
                    let candidate = &rest[..end];
                    if candidate.starts_with("http") && origin(candidate) != base_origin {
                        if let Ok(mut t) = task.lock() {
                            t.url = candidate.to_string();
                        }
                        return Ok(candidate.to_string());
                    }
                }
            }
        }

        // Waitlist page: "let waitSeconds = N;" — wait it out, then retry.
        if let Some(pos) = html.find("waitSeconds") {
            let tail = &html[pos..(pos + 60).min(html.len())];
            let digits: String = tail
                .chars()
                .skip_while(|c| !c.is_ascii_digit())
                .take_while(|c| c.is_ascii_digit())
                .collect();
            let seconds: u64 = digits.parse().unwrap_or(0);
            if seconds > 0 {
                if let Ok(mut t) = task.lock() {
                    t.state = "waiting".to_string();
                    t.error = Some(format!("Waiting {}s before download…", seconds));
                }
                let mut waited = 0u64;
                while waited < seconds.min(600) {
                    if cancelled.load(Ordering::Relaxed) {
                        return Err("cancelled".to_string());
                    }
                    std::thread::sleep(Duration::from_secs(1));
                    waited += 1;
                    if let Ok(mut t) = task.lock() {
                        t.error = Some(format!("Waiting {}s before download…", seconds.saturating_sub(waited)));
                    }
                }
                continue; // re-request the same URL
            }
        }

        // Page had neither link nor waitlist: likely blocked (Cloudflare/VPN notice).
        if html.contains("only_official") || html.contains("no_cloudflare") || html.contains("browser_verification") {
            return Err("This download server needs browser verification. Try a different server on the book page.".to_string());
        }
        return Err("Could not resolve the download link. Try a different server on the book page.".to_string());
    }
    Err("Download waitlist timed out. Try again shortly.".to_string())
}

pub fn start(
    req: DownloadRequest,
    cache_dir: &PathBuf,
    tasks: &Mutex<HashMap<String, Arc<Mutex<Task>>>>,
    library_has: impl Fn(&str) -> bool,
) -> Result<String, String> {
    let id = req.id.trim().to_string();
    if id.is_empty() {
        return Err("Missing book id".to_string());
    }
    let url = req.url.trim().to_string();
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("That download link is not a direct HTTP link.".to_string());
    }
    if !req.replace && library_has(&id) {
        return Err("This book is already in your library.".to_string());
    }

    let mut map = tasks.lock().map_err(|_| "state error".to_string())?;
    if let Some(existing) = map.get(&id) {
        if let Ok(t) = existing.lock() {
            if matches!(t.state.as_str(), "queued" | "downloading" | "waiting" | "saving") {
                return Err("This book is already downloading.".to_string());
            }
        }
    }

    // Prune finished tasks so the map cannot grow without bound.
    let mut finished: Vec<(i64, String)> = Vec::new();
    for (key, value) in map.iter() {
        if let Ok(task) = value.lock() {
            if matches!(task.state.as_str(), "done" | "cancelled" | "error") {
                finished.push((task.created_at, key.clone()));
            }
        }
    }
    if map.len() > 18 {
        finished.sort();
        for (_, key) in finished {
            if map.len() <= 15 {
                break;
            }
            map.remove(&key);
        }
    }

    let ext = {
        let from_req = req.format.to_lowercase();
        if is_document_ext(&from_req) {
            from_req
        } else {
            extension_of(&url, None)
        }
    };
    let filename = filename_for(&req.title, &req.authors, &ext);
    let temp_dir = cache_dir.join("downloads");
    fs::create_dir_all(&temp_dir).map_err(|e| format!("Could not create download cache: {}", e))?;
    let temp_path = temp_dir.join(temp_name(&id));

    let task = Task {
        id: id.clone(),
        title: if req.title.trim().is_empty() {
            title_from_url(&url)
        } else {
            req.title.clone()
        },
        authors: req.authors.clone(),
        format: ext.clone(),
        url,
        cover: req.cover.clone(),
        book_url: req.book_url.clone(),
        filename,
        state: "queued".to_string(),
        received: 0,
        total: 0,
        speed: 0,
        temp_path: temp_path.to_string_lossy().to_string(),
        error: None,
        created_at: now_ms(),
        cancelled: Arc::new(AtomicBool::new(false)),
    };
    let task = Arc::new(Mutex::new(task));
    map.insert(id.clone(), task.clone());
    drop(map);

    std::thread::spawn(move || run_download(task));

    Ok(id)
}

fn run_download(task: Arc<Mutex<Task>>) {
    let (url, temp_path, cancelled) = {
        let mut t = match task.lock() {
            Ok(t) => t,
            Err(_) => return,
        };
        t.state = "downloading".to_string();
        t.error = None;
        (t.url.clone(), t.temp_path.clone(), t.cancelled.clone())
    };

    // Anna's Archive slow downloads need a waitlist hop to a file URL first.
    let url = if url.contains("/slow_download/") {
        match resolve_slow_download(&task, &url, &cancelled) {
            Ok(final_url) => final_url,
            Err(e) => {
                if e == "cancelled" {
                    let _ = fs::remove_file(&temp_path);
                    if let Ok(mut t) = task.lock() {
                        t.state = "cancelled".to_string();
                        t.speed = 0;
                    }
                    return;
                }
                set_error(&task, e);
                return;
            }
        }
    } else {
        url
    };

    let part = PathBuf::from(&temp_path);
    let existing = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);

    let resp = match http::get_stream(&url, &origin(&url), Some(&format!("bytes={}-", existing))) {
        Ok(r) => r,
        Err(_) => {
            // Retry without range (fresh download) before failing.
            let _ = fs::remove_file(&part);
            match http::get_stream(&url, &origin(&url), None) {
                Ok(r) => r,
                Err(e) => {
                    set_error(&task, e);
                    return;
                }
            }
        }
    };

    let mut start_at = if resp.status() == 206 { existing } else { 0 };
    if resp.status() == 200 {
        start_at = 0;
    }

    // Never save a web page as a "file": guests of /fast_download/ are
    // redirected to a member-notice page, and blocked requests can return
    // HTML error pages.
    let final_url = resp.get_url().to_string();
    let ctype = resp.content_type().to_string();
    if final_url.contains("fast_download_not_member") {
        set_error(&task, "Fast downloads need a signed-in member account. Use a Slow Partner Server instead.".to_string());
        return;
    }
    if ctype.contains("text/html") {
        set_error(&task, "The server returned a web page instead of the file. Try a different download server on the book page.".to_string());
        return;
    }

    // Derive a nicer title/filename: prefer the server's Content-Disposition
    // name (the partner servers name the real file, e.g. "…_.mobi").
    if let Some(name) = filename_from_disposition(&resp) {
        if let Ok(mut t) = task.lock() {
            let ext_from_name = extension_of(&name, None);
            let (stem, ext) = match name.rfind('.') {
                Some(dot) => (&name[..dot], &name[dot + 1..]),
                None => (name.as_str(), ""),
            };
            let _ = ext_from_name;
            t.title = stem.to_string();
            if is_document_ext(ext) {
                t.format = ext.to_lowercase();
            }
            t.filename = if ext.is_empty() {
                sanitize_filename(stem)
            } else {
                format!("{}.{}", sanitize_filename(stem), ext)
            };
        }
    }
    if let Ok(mut t) = task.lock() {
        t.total = parse_total(&resp, start_at);
        t.received = start_at;
    }

    let mut file = match OpenOptions::new()
        .create(true)
        .write(true)
        .append(start_at > 0)
        .truncate(start_at == 0)
        .open(&part)
    {
        Ok(f) => f,
        Err(e) => {
            set_error(&task, format!("Could not open cache file: {}", e));
            return;
        }
    };

    let mut reader = resp.into_reader();
    let mut buffer = vec![0u8; 64 * 1024];
    let mut received = start_at;
    let mut last_mark = Instant::now();
    let mut last_bytes = received;

    loop {
        if cancelled.load(Ordering::Relaxed) {
            let _ = fs::remove_file(&part);
            if let Ok(mut t) = task.lock() {
                t.state = "cancelled".to_string();
                t.speed = 0;
            }
            return;
        }
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                if let Err(e) = file.write_all(&buffer[..n]) {
                    set_error(&task, format!("Could not write file: {}", e));
                    return;
                }
                received += n as u64;
                if let Ok(mut t) = task.lock() {
                    t.received = received;
                }
                let elapsed = last_mark.elapsed();
                if elapsed >= Duration::from_millis(400) {
                    let speed = ((received - last_bytes) as f64 / elapsed.as_secs_f64()) as u64;
                    if let Ok(mut t) = task.lock() {
                        t.speed = speed;
                        if t.total == 0 {
                            t.total = received;
                        }
                    }
                    last_mark = Instant::now();
                    last_bytes = received;
                }
            }
            Err(e) => {
                set_error(&task, format!("Download interrupted: {}", e));
                return;
            }
        }
    }

    if let Err(e) = file.flush() {
        set_error(&task, format!("Could not finish writing: {}", e));
        return;
    }
    drop(file);

    // Last-resort format detection: sniff the file's magic bytes when the
    // metadata and the server both left the extension unknown ("bin").
    if let Ok(mut t) = task.lock() {
        if !is_document_ext(&t.format) {
            if let Ok(head) = fs::read(&part).map(|b| b.into_iter().take(80).collect::<Vec<u8>>()) {
                let sniffed = if head.len() >= 68 && &head[60..68] == b"BOOKMOBI" {
                    Some("mobi")
                } else if head.starts_with(b"%PDF") {
                    Some("pdf")
                } else if head.starts_with(b"PK\x03\x04") {
                    Some("epub")
                } else {
                    None
                };
                if let Some(ext) = sniffed {
                    t.format = ext.to_string();
                    let stem = t.filename.rsplit_once('.').map(|x| x.0).unwrap_or(&t.filename).to_string();
                    t.filename = format!("{}.{}", sanitize_filename(&stem), ext);
                }
            }
        }
    }

    if let Ok(mut t) = task.lock() {
        t.received = received;
        if t.total < received {
            t.total = received;
        }
        t.speed = 0;
        t.state = "saving".to_string();
    }
}

fn set_error(task: &Arc<Mutex<Task>>, message: String) {
    if let Ok(mut t) = task.lock() {
        t.state = "error".to_string();
        t.error = Some(message);
        t.speed = 0;
    }
}

pub fn cancel(id: &str, tasks: &Mutex<HashMap<String, Arc<Mutex<Task>>>>) {
    if let Ok(map) = tasks.lock() {
        if let Some(task) = map.get(id) {
            if let Ok(t) = task.lock() {
                if t.state == "saving" {
                    return;
                }
                t.cancelled.store(true, Ordering::Relaxed);
            }
        }
    }
}

pub fn snapshots(tasks: &Mutex<HashMap<String, Arc<Mutex<Task>>>>) -> Vec<DownloadProgress> {
    let mut out = Vec::new();
    if let Ok(map) = tasks.lock() {
        for task in map.values() {
            if let Ok(t) = task.lock() {
                out.push(t.progress());
            }
        }
    }
    out.sort_by(|a, b| b.created_at.cmp(&a.created_at));
    out
}

pub fn finish_task(
    id: &str,
    uri: Option<String>,
    local_path: Option<String>,
    filename: String,
    size: u64,
    tasks: &Mutex<HashMap<String, Arc<Mutex<Task>>>>,
) -> Result<Option<LibraryItem>, String> {
    let task = {
        let map = tasks.lock().map_err(|_| "state error".to_string())?;
        map.get(id).cloned()
    };
    let task = match task {
        Some(t) => t,
        None => return Ok(None),
    };
    let (title, authors, format, cover, book_url, temp_path) = {
        let t = task.lock().map_err(|_| "state error".to_string())?;
        (
            t.title.clone(),
            t.authors.clone(),
            t.format.clone(),
            t.cover.clone(),
            t.book_url.clone(),
            t.temp_path.clone(),
        )
    };
    let final_name = if filename.trim().is_empty() {
        sanitize_filename(&title)
    } else {
        filename
    };
    let extension = extension_of(&final_name, None);
    let format = if is_document_ext(&extension) { extension } else { format };
    let mime = mime_for(&format);

    let _ = fs::remove_file(&temp_path);
    if let Ok(mut map) = tasks.lock() {
        map.remove(id);
    }

    Ok(Some(LibraryItem {
        id: id.to_string(),
        title,
        authors,
        format,
        size,
        cover,
        book_url,
        uri,
        local_path,
        filename: final_name,
        mime,
        saved_at: now_ms(),
        read: false,
    }))
}

pub fn fail_task(
    id: &str,
    message: String,
    tasks: &Mutex<HashMap<String, Arc<Mutex<Task>>>>,
) {
    if let Ok(map) = tasks.lock() {
        if let Some(task) = map.get(id) {
            if let Ok(mut t) = task.lock() {
                t.state = "error".to_string();
                t.error = Some(message);
            }
        }
    }
}

pub fn remove_temp(id: &str, tasks: &Mutex<HashMap<String, Arc<Mutex<Task>>>>) {
    if let Ok(map) = tasks.lock() {
        if let Some(task) = map.get(id) {
            if let Ok(t) = task.lock() {
                let _ = fs::remove_file(&t.temp_path);
            }
        }
    }
}
