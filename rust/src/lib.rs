mod download;
mod http;
mod library;
mod model;
mod scrape;
mod scrape_aa;
mod util;

use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

static LOGGED_IN: AtomicBool = AtomicBool::new(false);
use std::time::Instant;

use jni::objects::{JObject, JString};
use jni::sys::{jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;

use download::Task;
use library::Library;
use model::{
    BookResponse, DownloadProgress, DownloadRequest, LibraryItem, OkResponse, SearchPage,
};
use util::{join_url, origin, query_encode};

pub struct AppState {
    pub files_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub base_url: Mutex<String>,
    pub library: Mutex<Library>,
    pub tasks: Mutex<HashMap<String, Arc<Mutex<Task>>>>,
    pub images: Mutex<HashMap<String, Vec<u8>>>,
}

static APP: OnceLock<AppState> = OnceLock::new();

fn state() -> Option<&'static AppState> {
    APP.get()
}

fn normalize_base(raw: &str) -> String {
    let trimmed = raw.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        "https://annas-archive.gl".to_string()
    } else if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        trimmed.to_string()
    } else {
        format!("https://{}", trimmed)
    }
}

fn base_url() -> String {
    state()
        .and_then(|s| s.base_url.lock().ok().map(|b| b.clone()))
        .unwrap_or_else(|| "https://annas-archive.gl".to_string())
}

fn read_string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value)
        .map(|s| s.into())
        .unwrap_or_default()
}

fn json_error(message: impl Into<String>) -> String {
    serde_json::json!({ "ok": false, "error": message.into() }).to_string()
}

fn return_string(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn build_search_url(base: &str, query: &str, page: u32, ext: &str, lang: &str, sort: &str) -> String {
    let mut url = format!("{}/search?q={}", base.trim_end_matches('/'), query_encode(query));
    let ext = ext.trim();
    if !ext.is_empty() && !ext.eq_ignore_ascii_case("any") {
        url.push_str(&format!("&ext={}", query_encode(ext)));
    }
    let lang = lang.trim();
    if !lang.is_empty() && !lang.eq_ignore_ascii_case("any") {
        url.push_str(&format!("&lang={}", query_encode(lang)));
    }
    let sort = sort.trim();
    if !sort.is_empty() && !sort.eq_ignore_ascii_case("relevance") {
        url.push_str(&format!("&sort={}", query_encode(sort)));
    }
    if page > 1 {
        url.push_str(&format!("&page={}", page));
    }
    url
}

fn do_search(query: &str, page: u32, ext: &str, lang: &str, sort: &str) -> String {
    let started = Instant::now();
    let base = base_url();
    let trimmed = query.trim();
    if trimmed.is_empty() {
        return SearchPage {
            ok: true,
            error: None,
            query: String::new(),
            page,
            has_more: false,
            results: Vec::new(),
            took_ms: 0,
            source: base,
        }
        .serialize();
    }
    let mut results = Vec::new();
    let mut has_more = false;
    let mut source = String::new();
    let url = build_search_url(&base, trimmed, page.max(1), ext, lang, sort);
    match http::get_text(&url) {
        Ok(html) => {
            if is_challenge_page(&html) {
                return json_error("ddg_challenge: the site asked for a browser check");
            }
            let (r, h) = scrape_aa::parse_search_aa(&html, &base);
            if r.is_empty() {
                // Fallback to the legacy parser (other mirror layouts).
                let (r2, h2) = scrape::parse_search(&html, &base);
                results = r2;
                has_more = h2;
            } else {
                results = r;
                has_more = h;
            }
            source = url.clone();
        }
        Err(e) => {
            let msg = e.clone();
            if msg.contains("403") || msg.contains("protection") {
                return json_error("ddg_challenge: the site asked for a browser check");
            }
            return json_error(msg);
        }
    }
    // Fuzzy fallback: if the site returned nothing, broaden the query client-side.
    if results.is_empty() && trimmed.split_whitespace().count() > 1 {
        let words: Vec<&str> = trimmed.split_whitespace().collect();
        let mut candidates: Vec<String> = Vec::new();
        let filtered: Vec<&str> = words.iter().copied().filter(|w| {
            !matches!(w.to_lowercase().as_str(), "of" | "the" | "a" | "an" | "and" | "or" | "in" | "on" | "for" | "to")
        }).collect();
        if !filtered.is_empty() && filtered.len() < words.len() {
            candidates.push(filtered.join(" "));
        }
        if words.len() >= 2 {
            candidates.push(format!("{} {}", words[0], words[words.len() - 1]));
        }
        let mut longest = words[0];
        for w in &words {
            if w.len() > longest.len() { longest = w; }
        }
        candidates.push(longest.to_string());
        candidates.sort();
        candidates.dedup();

        let lower_words: Vec<String> = words.iter().map(|w| w.to_lowercase()).collect();
        for cand in &candidates {
            let fallback_url = build_search_url(&base, cand, 1, ext, lang, sort);
            let Ok(html) = http::get_text(&fallback_url) else { continue };
            if is_challenge_page(&html) { continue; }
            let (r, h) = scrape_aa::parse_search_aa(&html, &base);
            let (r, h) = if r.is_empty() { scrape::parse_search(&html, &base) } else { (r, h) };
            for item in r {
                let haystack = format!(
                    "{} {}",
                    item.title.to_lowercase(),
                    item.authors.join(" ").to_lowercase()
                );
                if lower_words.iter().all(|w| haystack.contains(w.as_str()))
                    && !results.iter().any(|x| x.id == item.id)
                {
                    results.push(item);
                }
            }
            if !results.is_empty() {
                source = fallback_url.clone();
                has_more = h;
                break;
            }
        }
        // If still empty, accept any results from the longest-word query (looser).
        if results.is_empty() {
            let fallback_url = build_search_url(&base, longest, 1, ext, lang, sort);
            if let Ok(html) = http::get_text(&fallback_url) {
                if !is_challenge_page(&html) {
                    let (r, h) = scrape_aa::parse_search_aa(&html, &base);
                    let (r, h) = if r.is_empty() { scrape::parse_search(&html, &base) } else { (r, h) };
                    for item in r {
                        if !results.iter().any(|x| x.id == item.id) {
                            results.push(item);
                        }
                    }
                    if !results.is_empty() {
                        source = fallback_url;
                        has_more = h;
                    }
                }
            }
        }
    }
    // Relevance sort: most matched query words first.
    let lower_query_words: Vec<String> = trimmed.split_whitespace().map(|w| w.to_lowercase()).collect();
    if sort.eq_ignore_ascii_case("relevance") || sort.trim().is_empty() {
        results.sort_by(|a, b| {
            let score = |r: &crate::model::SearchResult| -> usize {
                let haystack = format!("{} {}", r.title.to_lowercase(), r.authors.join(" ").to_lowercase());
                lower_query_words.iter().filter(|w| haystack.contains(w.as_str())).count()
            };
            let sa = score(a);
            let sb = score(b);
            if sa != sb { return sb.cmp(&sa); }
            std::cmp::Ordering::Equal
        });
    }
    SearchPage {
        ok: true,
        error: None,
        query: trimmed.to_string(),
        page: page.max(1),
        has_more,
        results,
        took_ms: started.elapsed().as_millis() as u64,
        source,
    }
    .serialize()
}

/// True when the page is a DDoS-Guard / bot-protection interstitial.
/// Interstitials are tiny (a few hundred bytes to a couple of KB); real pages
/// (hundreds of KB) also mention ddos-guard in script tags, so the size cap
/// avoids false positives.
fn is_challenge_page(html: &str) -> bool {
    if html.len() > 20_000 { return false; }
    let lower = html.to_lowercase();
    lower.contains("ddos-guard") || lower.contains("checking your browser") || lower.contains("just a moment")
}

/// Parse an already-fetched search page (fetched by the WebView bridge).
fn parse_search_html(html: &str, query: &str, page: u32) -> String {
    let started = Instant::now();
    let base = base_url();
    if is_challenge_page(html) {
        return json_error("ddg_challenge: the site asked for a browser check");
    }
    let (results, has_more) = scrape_aa::parse_search_aa(html, &base);
    let results = if results.is_empty() { scrape::parse_search(html, &base).0 } else { results };
    let lower_query_words: Vec<String> = query
        .trim()
        .split_whitespace()
        .map(|w| w.to_lowercase())
        .collect();
    let mut results = results;
    results.sort_by(|a, b| {
        let score = |r: &crate::model::SearchResult| -> usize {
            let haystack = format!("{} {}", r.title.to_lowercase(), r.authors.join(" ").to_lowercase());
            lower_query_words.iter().filter(|w| haystack.contains(w.as_str())).count()
        };
        score(b).cmp(&score(a))
    });
    SearchPage {
        ok: true,
        error: None,
        query: query.trim().to_string(),
        page,
        has_more,
        results,
        took_ms: started.elapsed().as_millis() as u64,
        source: String::new(),
    }
    .serialize()
}

trait SerializeJson {
    fn serialize(&self) -> String;
}

impl SerializeJson for SearchPage {
    fn serialize(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| json_error("Could not encode results"))
    }
}

fn book_target(value: &str) -> String {
    let base = base_url();
    let value = value.trim();
    if value.starts_with("http://") || value.starts_with("https://") {
        return value.to_string();
    }
    if value.len() == 32 && value.chars().all(|c| c.is_ascii_hexdigit()) {
        return format!("{}/md5/{}", base.trim_end_matches('/'), value);
    }
    format!("{}/book/{}", base.trim_end_matches('/'), value)
}

fn md5_from_target(value: &str) -> Option<String> {
    let v = value.trim();
    // "md5:<hex>" | bare hex | "/md5/<hex>" | full url
    if let Some(rest) = v.strip_prefix("md5:") {
        let hex: String = rest.chars().take_while(|c| c.is_ascii_hexdigit()).collect();
        if hex.len() == 32 { return Some(hex.to_lowercase()); }
    }
    let path = v.split(['?', '#']).next().unwrap_or(v);
    if let Some(idx) = path.find("/md5/") {
        let hex: String = path[idx + 5..].chars().take_while(|c| c.is_ascii_hexdigit()).collect();
        if hex.len() == 32 { return Some(hex.to_lowercase()); }
    }
    if v.len() == 32 && v.chars().all(|c| c.is_ascii_hexdigit()) {
        return Some(v.to_lowercase());
    }
    None
}

fn do_book(value: &str) -> String {
    let started = Instant::now();
    let base = base_url();
    let url = book_target(value);

    // Preferred path: the structured JSON the site itself consumes.
    if let Some(md5) = md5_from_target(value) {
        let json_url = format!("{}/db/aarecord/md5:{}.json", base.trim_end_matches('/'), md5);
        match http::get_text(&json_url) {
            Ok(json) => {
                if is_challenge_page(&json) {
                    return json_error("ddg_challenge: the site asked for a browser check");
                }
                if let Some(book) = scrape_aa::parse_book_aa_json(&json, &base, &url) {
                    let response = BookResponse {
                        ok: true,
                        error: None,
                        book: Some(book),
                        took_ms: started.elapsed().as_millis() as u64,
                    };
                    return serde_json::to_string(&response)
                        .unwrap_or_else(|_| json_error("Could not encode book"));
                }
                // fall through to HTML page parsing
            }
            Err(e) => {
                if e.contains("403") || e.contains("protection") {
                    return json_error("ddg_challenge: the site asked for a browser check");
                }
                if !e.contains("404") {
                    return json_error(e);
                }
                // 404 on JSON: fall through to the HTML page.
            }
        }
    }

    let response = match http::get_text(&url) {
        Ok(html) => {
            if is_challenge_page(&html) {
                return json_error("ddg_challenge: the site asked for a browser check");
            }
            let book = match scrape_aa::parse_book_aa_html(&html, &base, &url) {
                Some(b) => b,
                None => scrape::parse_book(&html, &base, &url),
            };
            if book.title == "Untitled" && book.metadata.is_empty() && book.authors.is_empty() {
                return json_error("This book page could not be read. It may have been removed.");
            }
            BookResponse {
                ok: true,
                error: None,
                book: Some(book),
                took_ms: started.elapsed().as_millis() as u64,
            }
        }
        Err(e) => return json_error(e),
    };
    serde_json::to_string(&response).unwrap_or_else(|_| json_error("Could not encode book"))
}

/// Parse a JSON record already fetched by the WebView bridge.
fn parse_book_json(json: &str, source_url: &str) -> String {
    let started = Instant::now();
    let base = base_url();
    let url = if source_url.trim().is_empty() {
        // Derive the page URL from the record id inside the JSON.
        let parsed: Result<serde_json::Value, _> = serde_json::from_str(json);
        let id = parsed
            .ok()
            .and_then(|v| v.get("id").and_then(|x| x.as_str()).map(|s| s.to_string()));
        match id.as_deref().and_then(md5_from_target) {
            Some(md5) => format!("{}/md5/{}", base.trim_end_matches('/'), md5),
            None => base.clone(),
        }
    } else {
        book_target(source_url)
    };
    match scrape_aa::parse_book_aa_json(json, &base, &url) {
        Some(book) => {
            let response = BookResponse {
                ok: true,
                error: None,
                book: Some(book),
                took_ms: started.elapsed().as_millis() as u64,
            };
            serde_json::to_string(&response).unwrap_or_else(|_| json_error("Could not encode book"))
        }
        None => json_error("Could not read the book record"),
    }
}

/// Parse an /md5/<hash> HTML page already fetched by the WebView bridge.
fn parse_book_html(html: &str, source_url: &str) -> String {
    let started = Instant::now();
    let base = base_url();
    let url = book_target(source_url);
    match scrape_aa::parse_book_aa_html(html, &base, &url) {
        Some(book) => {
            let response = BookResponse {
                ok: true,
                error: None,
                book: Some(book),
                took_ms: started.elapsed().as_millis() as u64,
            };
            serde_json::to_string(&response).unwrap_or_else(|_| json_error("Could not encode book"))
        }
        None => json_error("Could not read the book page"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_initCore(
    mut env: JNIEnv,
    _this: JObject,
    files_dir: JString,
    cache_dir: JString,
    base: JString,
) -> jstring {
    let files = PathBuf::from(read_string(&mut env, &files_dir));
    let cache = PathBuf::from(read_string(&mut env, &cache_dir));
    let base = normalize_base(&read_string(&mut env, &base));
    let _ = fs::create_dir_all(&files);
    let _ = fs::create_dir_all(&cache);
    let lib_path = files.join("library.json");
    let store = AppState {
        files_dir: files,
        cache_dir: cache,
        base_url: Mutex::new(base.clone()),
        library: Mutex::new(Library::load(lib_path)),
        tasks: Mutex::new(HashMap::new()),
        images: Mutex::new(HashMap::new()),
    };
    if APP.set(store).is_err() {
        if let Some(st) = state() {
            if let Ok(mut b) = st.base_url.lock() {
                *b = base;
            }
        }
    }
    return_string(&mut env, "{\"ok\":true}".to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_setBaseUrl(
    mut env: JNIEnv,
    _this: JObject,
    base: JString,
) -> jstring {
    let base = normalize_base(&read_string(&mut env, &base));
    if let Some(st) = state() {
        if let Ok(mut b) = st.base_url.lock() {
            *b = base;
        }
    }
    return_string(&mut env, "{\"ok\":true}".to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_setCookies(
    mut env: JNIEnv,
    _this: JObject,
    header: JString,
) -> jstring {
    let header = read_string(&mut env, &header);
    http::set_cookies(&header);
    return_string(&mut env, "{\"ok\":true}".to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_parseSearchHtml(
    mut env: JNIEnv,
    _this: JObject,
    html: JString,
    query: JString,
    page: jint,
) -> jstring {
    let html = read_string(&mut env, &html);
    let query = read_string(&mut env, &query);
    let page = page.max(1) as u32;
    let result = parse_search_html(&html, &query, page);
    return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_parseBookHtml(
    mut env: JNIEnv,
    _this: JObject,
    html: JString,
    url: JString,
) -> jstring {
    let html = read_string(&mut env, &html);
    let url = read_string(&mut env, &url);
    let result = parse_book_html(&html, &url);
    return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_parseBookJson(
    mut env: JNIEnv,
    _this: JObject,
    json: JString,
    url: JString,
) -> jstring {
    let json = read_string(&mut env, &json);
    let url = read_string(&mut env, &url);
    let result = parse_book_json(&json, &url);
    return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_search(
    mut env: JNIEnv,
    _this: JObject,
    query: JString,
    page: jint,
    ext: JString,
    lang: JString,
    sort: JString,
) -> jstring {
    let query = read_string(&mut env, &query);
    let ext = read_string(&mut env, &ext);
    let lang = read_string(&mut env, &lang);
    let sort = read_string(&mut env, &sort);
    let result = do_search(&query, page.max(1) as u32, &ext, &lang, &sort);
    return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_book(
    mut env: JNIEnv,
    _this: JObject,
    url: JString,
) -> jstring {
    let url = read_string(&mut env, &url);
    let result = do_book(&url);
    return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_startDownload(
    mut env: JNIEnv,
    _this: JObject,
    request: JString,
) -> jstring {
    let raw = read_string(&mut env, &request);
    let req: DownloadRequest = match serde_json::from_str(&raw) {
        Ok(r) => r,
        Err(e) => return return_string(&mut env, json_error(format!("Bad download request: {}", e))),
    };
    let st = match state() {
        Some(s) => s,
        None => return return_string(&mut env, json_error("Core is not ready")),
    };
    let has = |id: &str| st.library.lock().map(|l| l.get(id).is_some()).unwrap_or(false);
    match download::start(req.clone(), &st.cache_dir, &st.tasks, has) {
        Ok(id) => {
            let payload = serde_json::json!({ "ok": true, "id": id }).to_string();
            return_string(&mut env, payload)
        }
        Err(e) => return_string(&mut env, json_error(e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_downloads(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let tasks = match state() {
        Some(s) => download::snapshots(&s.tasks),
        None => Vec::<DownloadProgress>::new(),
    };
    let payload = serde_json::to_string(&tasks)
        .unwrap_or_else(|_| "[]".to_string());
    return_string(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_cancelDownload(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
) -> jstring {
    let id = read_string(&mut env, &id);
    if let Some(st) = state() {
        download::cancel(&id, &st.tasks);
    }
    return_string(&mut env, "{\"ok\":true}".to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_finishSave(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
    uri: JString,
    local_path: JString,
    filename: JString,
    size: jlong,
) -> jstring {
    let id = read_string(&mut env, &id);
    let uri = read_string(&mut env, &uri);
    let local_path = read_string(&mut env, &local_path);
    let filename = read_string(&mut env, &filename);
    let st = match state() {
        Some(s) => s,
        None => return return_string(&mut env, json_error("Core is not ready")),
    };
    let size = size.max(0) as u64;
    let item = {
        match download::finish_task(
            &id,
            if uri.is_empty() { None } else { Some(uri) },
            if local_path.is_empty() { None } else { Some(local_path) },
            filename,
            size,
            &st.tasks,
        ) {
            Ok(item) => item,
            Err(e) => return return_string(&mut env, json_error(e)),
        }
    };
    let response = match item {
        Some(item) => match st.library.lock() {
            Ok(mut lib) => match lib.upsert(item) {
                Ok(saved) => OkResponse { ok: true, error: None, item: Some(saved) },
                Err(e) => OkResponse { ok: false, error: Some(e), item: None },
            },
            Err(_) => OkResponse { ok: false, error: Some("state error".into()), item: None },
        },
        None => OkResponse {
            ok: false,
            error: Some("Download task no longer exists".into()),
            item: None,
        },
    };
    let payload = serde_json::to_string(&response).unwrap_or_else(|_| json_error("Could not save"));
    return_string(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_failSave(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
    message: JString,
) -> jstring {
    let id = read_string(&mut env, &id);
    let message = read_string(&mut env, &message);
    if let Some(st) = state() {
        download::fail_task(&id, message, &st.tasks);
        download::remove_temp(&id, &st.tasks);
    }
    return_string(&mut env, "{\"ok\":true}".to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_library(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let items: Vec<LibraryItem> = state()
        .and_then(|s| s.library.lock().ok().map(|l| l.all()))
        .unwrap_or_default();
    let payload = serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string());
    return_string(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_libraryItem(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
) -> jstring {
    let id = read_string(&mut env, &id);
    let item = state().and_then(|s| s.library.lock().ok().and_then(|l| l.get(&id)));
    let response = match item {
        Some(item) => OkResponse { ok: true, error: None, item: Some(item) },
        None => OkResponse { ok: false, error: Some("Not found".into()), item: None },
    };
    let payload = serde_json::to_string(&response).unwrap_or_else(|_| json_error("Could not read item"));
    return_string(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_setRead(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
    read: jni::sys::jboolean,
) -> jstring {
    let id = read_string(&mut env, &id);
    let want = read != 0;
    let result = state()
        .and_then(|s| s.library.lock().ok().map(|mut lib| lib.set_read(&id, want)));
    let response = match result {
        Some(Ok(Some(item))) => OkResponse { ok: true, error: None, item: Some(item) },
        Some(Ok(None)) => OkResponse { ok: false, error: Some("Not found".into()), item: None },
        Some(Err(e)) => OkResponse { ok: false, error: Some(e), item: None },
        None => OkResponse { ok: false, error: Some("state error".into()), item: None },
    };
    let payload = serde_json::to_string(&response).unwrap_or_else(|_| json_error("Could not update"));
    return_string(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_deleteItem(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
) -> jstring {
    let id = read_string(&mut env, &id);
    let result = state().and_then(|s| s.library.lock().ok().map(|mut lib| lib.remove(&id)));
    let response = match result {
        Some(Ok(Some(item))) => OkResponse { ok: true, error: None, item: Some(item) },
        Some(Ok(None)) => OkResponse { ok: false, error: Some("Not found".into()), item: None },
        Some(Err(e)) => OkResponse { ok: false, error: Some(e), item: None },
        None => OkResponse { ok: false, error: Some("state error".into()), item: None },
    };
    let payload = serde_json::to_string(&response).unwrap_or_else(|_| json_error("Could not delete"));
    return_string(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_fetchBytes(
    mut env: JNIEnv,
    _this: JObject,
    url: JString,
) -> jbyteArray {
    let url = read_string(&mut env, &url);
    if url.is_empty() {
        return std::ptr::null_mut();
    }
    let abs = join_url(&base_url(), &url);
    if let Some(st) = state() {
        if let Ok(cache) = st.images.lock() {
            if let Some(bytes) = cache.get(&abs) {
                return match env.byte_array_from_slice(bytes) {
                    Ok(arr) => arr.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        }
    }
    let bytes = match http::get_bytes(&abs, 8 * 1024 * 1024) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    if let Some(st) = state() {
        if let Ok(mut cache) = st.images.lock() {
            if cache.len() > 48 {
                cache.clear();
            }
            cache.insert(abs.clone(), bytes.clone());
        }
    }
    match env.byte_array_from_slice(&bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn regex_lite_token(html: &str) -> String {
    // Extract value of <input name="_token" value="...">
    if let Some(idx) = html.find("name=\"_token\"") {
        let tail = &html[idx..];
        if let Some(v) = tail.find("value=\"") {
            let start = v + 7;
            let rest = &tail[start..];
            if let Some(end) = rest.find('"') {
                return rest[..end].to_string();
            }
        }
    }
    if let Some(idx) = html.find("name='_token'") {
        let tail = &html[idx..];
        if let Some(v) = tail.find("value='") {
            let start = v + 7;
            let rest = &tail[start..];
            if let Some(end) = rest.find('\'') {
                return rest[..end].to_string();
            }
        }
    }
    String::new()
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_login(
    mut env: JNIEnv,
    _this: JObject,
    _email: JString,
    _password: JString,
) -> jstring {
    return_string(&mut env, json_error(
        "This mirror uses single sign-on; in-app login is not available. Use 'Request this book' to get books via the site."
    ))
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_logout(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    LOGGED_IN.store(false, Ordering::Relaxed);
    return_string(&mut env, "{\"ok\":true}".to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_isLoggedIn(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let logged = LOGGED_IN.load(Ordering::Relaxed);
    let payload = serde_json::json!({"ok": true, "logged_in": logged}).to_string();
    return_string(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_com_monochrome_annas_Native_storageStats(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let (count, bytes, read) = state()
        .and_then(|s| {
            s.library.lock().ok().map(|l| {
                let items = l.all();
                let read = items.iter().filter(|i| i.read).count();
                (items.len(), l.total_bytes(), read)
            })
        })
        .unwrap_or((0, 0, 0));
    let payload = serde_json::json!({
        "ok": true,
        "count": count,
        "bytes": bytes,
        "read": read,
        "unread": count.saturating_sub(read),
        "origin": origin(&base_url()),
    })
    .to_string();
    return_string(&mut env, payload)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicBool;
    use std::sync::Mutex;
    use std::time::Duration;

    // Live network test. ureq can no longer reach annas-archive.gl directly
    // (DDoS-Guard); all live fetching goes through the on-device WebView
    // bridge, so this only passes when run with valid ddg cookies.
    #[test]
    #[ignore = "requires DDoS-Guard cookies; live search runs via the WebView bridge"]
    fn live_search_variety() {
        let queries = ["empire vamp", "empire of the vampire", "clean code", "dune"];
        for q in queries {
            let search = do_search(q, 1, "", "", "");
            let value: serde_json::Value = serde_json::from_str(&search).unwrap();
            assert_eq!(value["ok"], true, "search failed for {}: {}", q, search);
            let results = value["results"].as_array().cloned().unwrap_or_default();
            println!("{} -> {} results", q, results.len());
        }
    }
}
