use std::time::{SystemTime, UNIX_EPOCH};

pub fn clean(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn origin(base: &str) -> String {
    if let Some(pos) = base.find("://") {
        let rest = &base[pos + 3..];
        let end = rest.find('/').unwrap_or(rest.len());
        format!("{}{}", &base[..pos + 3], &rest[..end])
    } else {
        base.trim_end_matches('/').to_string()
    }
}

pub fn join_url(base: &str, href: &str) -> String {
    let href = href.trim();
    if href.starts_with("http://") || href.starts_with("https://") {
        return href.to_string();
    }
    if href.starts_with("//") {
        let scheme = base.split("://").next().unwrap_or("https");
        return format!("{}:{}", scheme, href);
    }
    let origin = origin(base);
    if href.starts_with('/') {
        return format!("{}{}", origin, href);
    }
    let base_no_q = base.split(['?', '#']).next().unwrap_or(base);
    let prefix = match base_no_q.rfind('/') {
        Some(i) if i > origin.len() => &base_no_q[..=i],
        _ => return format!("{}/{}", origin, href),
    };
    format!("{}{}", prefix, href)
}

pub fn query_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

pub fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
            if let Ok(v) = u8::from_str_radix(hex, 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        if bytes[i] == b'+' {
            out.push(b' ');
        } else {
            out.push(bytes[i]);
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

pub fn sanitize_filename(name: &str) -> String {
    let mut out = String::with_capacity(name.len());
    for ch in name.chars() {
        match ch {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '\n' | '\r' | '\t' => {
                out.push(' ')
            }
            c if (c as u32) < 32 => out.push(' '),
            c => out.push(c),
        }
    }
    let collapsed = out.split_whitespace().collect::<Vec<_>>().join(" ");
    let trimmed = collapsed.trim_matches(|c: char| c == '.' || c == ' ');
    if trimmed.is_empty() {
        "download".to_string()
    } else if trimmed.chars().count() > 90 {
        let cut: String = trimmed.chars().take(90).collect();
        cut.trim_end().to_string()
    } else {
        trimmed.to_string()
    }
}

pub fn extension_of(name: &str, mime: Option<&str>) -> String {
    let base = name.split(['?', '#']).next().unwrap_or(name);
    if let Some(dot) = base.rfind('.') {
        if dot + 1 < base.len() {
            let ext: String = base[dot + 1..]
                .chars()
                .filter(|c| c.is_ascii_alphanumeric())
                .collect();
            if !ext.is_empty() && ext.len() <= 5 && !ext.chars().all(|c| c.is_ascii_digit()) {
                return ext.to_lowercase();
            }
        }
    }
    match mime.unwrap_or("") {
        "application/epub+zip" => "epub".into(),
        "application/pdf" => "pdf".into(),
        "application/x-mobipocket-ebook" => "mobi".into(),
        "text/plain" => "txt".into(),
        _ => "bin".into(),
    }
}

pub fn mime_for(format: &str) -> String {
    match format.to_lowercase().as_str() {
        "epub" => "application/epub+zip",
        "pdf" => "application/pdf",
        "mobi" => "application/x-mobipocket-ebook",
        "azw3" | "azw" => "application/vnd.amazon.ebook",
        "djvu" => "image/vnd.djvu",
        "fb2" => "application/x-fictionbook+xml",
        "txt" => "text/plain",
        "cbz" => "application/vnd.comicbook+zip",
        "cbr" => "application/vnd.comicbook-rar",
        "zip" => "application/zip",
        "webp" => "image/webp",
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "gif" => "image/gif",
        _ => "application/octet-stream",
    }
    .to_string()
}

pub fn is_document_ext(ext: &str) -> bool {
    matches!(
        ext,
        "epub" | "pdf" | "mobi" | "azw3" | "azw" | "djvu" | "fb2" | "txt" | "cbz" | "cbr" | "zip"
    )
}

pub fn is_image_ext(ext: &str) -> bool {
    matches!(ext, "jpg" | "jpeg" | "png" | "webp" | "gif" | "svg" | "avif")
}
