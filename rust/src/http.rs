use std::io::Read;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
static COOKIES: Mutex<String> = Mutex::new(String::new());

pub const USER_AGENT: &str =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) \
     Chrome/127.0.0.0 Mobile Safari/537.36";

/// Install a raw Cookie header string, e.g. "k1=v1; k2=v2".
/// Called from the UI layer after a WebView has solved any bot check,
/// so the native HTTP client inherits the browser session.
pub fn set_cookies(header: &str) {
    if let Ok(mut c) = COOKIES.lock() {
        *c = header.trim().to_string();
    }
}

pub fn cookie_header() -> Option<String> {
    COOKIES
        .lock()
        .ok()
        .map(|c| c.trim().to_string())
        .filter(|c| !c.is_empty())
}

pub fn agent() -> &'static ureq::Agent {
    AGENT.get_or_init(|| {
        ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(20))
            .timeout_read(Duration::from_secs(45))
            .timeout_write(Duration::from_secs(20))
            .redirects(8)
            .user_agent(USER_AGENT)
            .build()
    })
}

/// Build a request with the standard browser-like headers + synced cookies.
pub fn get(url: &str) -> ureq::Request {
    let mut req = agent()
        .get(url)
        .set("Accept", "text/html,application/json,application/xhtml+xml,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9");
    if let Some(cookies) = cookie_header() {
        req = req.set("Cookie", &cookies);
    }
    req
}

fn describe(err: ureq::Error) -> String {
    match err {
        ureq::Error::Status(code, _resp) => {
            if code == 403 { "Blocked by the site protection (HTTP 403). Opening the site in-app to refresh the session may help.".to_string() }
            else if code == 404 { "Not found on the site (HTTP 404)".to_string() }
            else if code == 429 { "The site is rate limiting requests (HTTP 429). Try again shortly.".to_string() }
            else if code >= 500 { format!("The site is having trouble (HTTP {}). Try again shortly.", code) }
            else { format!("Request failed (HTTP {})", code) }
        }
        ureq::Error::Transport(t) => format!("Network error: {}", t.message().unwrap_or("connection failed")),
    }
}

/// True when the server answered with a bot-protection challenge page.
pub fn is_challenge(resp: &ureq::Response) -> bool {
    resp.status() == 403 || resp.status() == 503
}

pub fn get_text(url: &str) -> Result<String, String> {
    let resp = get(url).call().map_err(describe)?;
    resp.into_string().map_err(|e| format!("Could not read response: {}", e))
}

pub fn get_bytes(url: &str, max: usize) -> Result<Vec<u8>, String> {
    let mut req = agent()
        .get(url)
        .set("Accept", "image/*,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9");
    if let Some(cookies) = cookie_header() {
        req = req.set("Cookie", &cookies);
    }
    let resp = req.call().map_err(describe)?;
    let mut reader = resp.into_reader();
    let mut buf = Vec::new();
    let mut tmp = [0u8; 64 * 1024];
    loop {
        if buf.len() > max { break; }
        match reader.read(&mut tmp) {
            Ok(0) => break,
            Ok(n) => { buf.extend_from_slice(&tmp[..n]); }
            Err(e) => return Err(format!("Could not read image: {}", e)),
        }
    }
    if buf.len() > max { return Err("Image is too large".to_string()); }
    Ok(buf)
}

/// Streaming GET used by the download engine.
pub fn get_stream(url: &str, referer: &str, range: Option<&str>) -> Result<ureq::Response, String> {
    let mut req = agent()
        .get(url)
        .set("Accept", "*/*")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Referer", referer);
    if let Some(cookies) = cookie_header() {
        req = req.set("Cookie", &cookies);
    }
    if let Some(r) = range {
        req = req.set("Range", r);
    }
    req.call().map_err(describe)
}

pub fn describe_error(err: ureq::Error) -> String { describe(err) }
