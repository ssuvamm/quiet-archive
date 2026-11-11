// Anna's Archive (annas-archive.gl) parsers.
//
// Search cards: <a href="/md5/<hex>"> containing h3 (title), div.truncate
// (publisher), div.italic (author), img (cover), plus a small top_row line.
// Results beyond the first batch are wrapped in HTML comments which the site
// reveals via JS — we unhide them the same way before parsing.
//
// Book pages come from the structured /db/aarecord/<id>.json endpoint, which
// the site itself uses.

use once_cell::sync::Lazy;
use scraper::{ElementRef, Html, Selector};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_real_search_page() {
        let html = include_str!("../test_data/aa_search.html");
        let (results, has_more) = parse_search_aa(html, "https://annas-archive.gl");
        assert!(results.len() >= 40, "got {} results", results.len());
        assert!(has_more);
        let r = &results[0];
        println!("first: {:?}", r);
        assert!(!r.title.is_empty() && r.title != "Untitled");
        assert!(
            r.authors.iter().any(|a| a.contains("Kristoff")),
            "authors={:?}",
            r.authors
        );
        assert_eq!(r.language.as_deref(), Some("English"));
        assert_eq!(r.format.as_deref(), Some("MOBI"));
        assert!(r.size.as_deref().unwrap_or("").contains("35.5"));
        assert_eq!(r.year.as_deref(), Some("2019"));
        assert!(r.publisher.as_deref().unwrap_or("").contains("HarperCollins"));
        assert!(r.cover.as_deref().unwrap_or("").starts_with("https://"));
    }

    #[test]
    fn parses_real_book_page() {
        let html = include_str!("../test_data/aa_book.html");
        let book = parse_book_aa_html(
            html,
            "https://annas-archive.gl",
            "https://annas-archive.gl/md5/0c3f6402d3d17b84996628c2d36c204c",
        )
        .expect("should parse");
        println!("{:?}", book);
        assert_eq!(book.id, "0c3f6402d3d17b84996628c2d36c204c");
        assert!(
            book.title.starts_with("Empire of the Vampire"),
            "title={:?}",
            book.title
        );
        assert!(!book.title.contains("search"), "title={:?}", book.title);
        assert!(
            book.authors.iter().any(|a| a.contains("Kristoff")),
            "authors={:?}",
            book.authors
        );
        assert!(
            book.metadata
                .iter()
                .any(|m| m.label == "Publisher" && m.value.contains("HarperCollins")),
            "metadata={:?}",
            book.metadata
        );
        assert!(book.cover.as_deref().unwrap_or("").starts_with("https://"));
        assert!(book.description.as_deref().unwrap_or("").len() > 50);
        assert!(
            book.downloads.iter().any(|d| d.kind == "fast"),
            "downloads={:?}",
            book.downloads.iter().map(|d| d.kind.clone()).collect::<Vec<_>>()
        );
    }
}

use crate::model::{Book, LinkRow, MetaRow, SearchResult};
use crate::util::{clean, join_url};

fn sel(css: &str) -> Selector {
    Selector::parse(css).expect("valid selector")
}

static S_AA_LINK: Lazy<Selector> = Lazy::new(|| sel("a[href^=\"/md5/\"]"));
static S_AA_H3: Lazy<Selector> = Lazy::new(|| sel("h3"));
static S_AA_IMG: Lazy<Selector> = Lazy::new(|| sel("img"));
static S_AA_DIV: Lazy<Selector> = Lazy::new(|| sel("div"));
static S_ALL_A: Lazy<Selector> = Lazy::new(|| sel("a[href]"));
// Real annas-archive.gl card anatomy (fetched 2026-11):
//   <div class="flex  pt-3 pb-3 border-b last:border-b-0 border-gray-100">
//     <a href="/md5/<hex>" class="custom-a block …">  ← cover img only
//     <div class="max-w-full overflow-hidden …">
//       <div>
//         <div class="line-clamp-[2] … font-mono">zlib/…/file.mobi</div>
//         <a href="/md5/<hex>" class="… text-lg …">TITLE</a>
//         <a href="/search?q=…"><span class="icon-[mdi--user-edit]"/> AUTHOR</a>
//         <a href="/search?q=…"><span class="icon-[mdi--company]"/> PUBLISHER, 2019</a>
//       </div>
//       <div> description … </div>
//       <div class="text-gray-800 …">English [en] · MOBI · 35.5MB · 2019 · …</div>
static S_CARD: Lazy<Selector> = Lazy::new(|| sel("div.flex.border-b"));
static S_SEARCH_LINK: Lazy<Selector> = Lazy::new(|| sel("a[href^=\"/search?q=\"]"));
static S_SPAN: Lazy<Selector> = Lazy::new(|| sel("span"));
static S_TOPROW: Lazy<Selector> = Lazy::new(|| sel("div.font-semibold"));
// /md5/<hash> book page.
static S_BOOK_TOPBOX: Lazy<Selector> = Lazy::new(|| sel("div.p-6"));
static S_BOOK_TITLE: Lazy<Selector> = Lazy::new(|| sel("div.text-2xl"));
static S_BOOK_TOPROW: Lazy<Selector> = Lazy::new(|| sel("div.text-gray-800"));
static S_BOOK_DESC: Lazy<Selector> = Lazy::new(|| sel("div.js-md5-top-box-description"));
static S_MB1: Lazy<Selector> = Lazy::new(|| sel("div.mb-1"));
static S_BOOK_DLNK: Lazy<Selector> = Lazy::new(|| sel("a.js-download-link"));

fn text(el: &ElementRef) -> String {
    clean(&el.text().collect::<Vec<_>>().join(" "))
}

/// Render an element's inner HTML as text, cutting it at the first of the
/// given markers (e.g. inline `<script>` or trailing icon/link markup) and
/// decoding entities via a fragment parse.
fn fragment_text(inner_html: &str, cut_at: &[&str]) -> String {
    let mut cut = inner_html;
    for marker in cut_at {
        if let Some(idx) = cut.find(marker) {
            cut = &cut[..idx];
        }
    }
    let frag = Html::parse_fragment(cut);
    clean(
        &frag
            .root_element()
            .descendants()
            .filter_map(|n| n.value().as_text().map(|t| t.as_ref() as &str))
            .collect::<Vec<_>>()
            .join(" "),
    )
}

fn attr(el: &ElementRef, name: &str) -> Option<String> {
    el.value().attr(name).map(|v| clean(v)).filter(|v| !v.is_empty())
}

/// Extract a 32-char hex md5 from an href like "/md5/<hex>".
fn md5_from_href(href: &str) -> Option<String> {
    let path = href.split(['?', '#']).next().unwrap_or(href);
    let idx = path.find("/md5/")?;
    let rest = &path[idx + 5..];
    let hex: String = rest.chars().take_while(|c| c.is_ascii_hexdigit()).collect();
    if hex.len() == 32 { Some(hex) } else { None }
}

fn strip_html(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut in_tag = false;
    for ch in raw.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => in_tag = false,
            c if !in_tag => out.push(c),
            _ => {}
        }
    }
    clean(&out)
}

/// Split the AA search top_row: "English [en] · MOBI · 35.5MB · 2019 · Book (fiction) · /zlib"
/// Returns (language, format, size, year).
fn split_top_row(top_row: &str) -> (Option<String>, Option<String>, Option<String>, Option<String>) {
    let mut language = None;
    let mut format = None;
    let mut size = None;
    let mut year = None;
    for part in top_row.split('·') {
        let p = part.trim();
        if p.is_empty() { continue; }
        if let Some(open) = p.find('[') {
            if let Some(close) = p.find(']') {
                if close > open && close - open == 3 {
                    language = Some(p[..open].trim().to_string());
                    continue;
                }
            }
        }
        if p.len() == 4 && p.chars().all(|c| c.is_ascii_digit()) {
            year = Some(p.to_string());
            continue;
        }
        let lower = p.to_lowercase();
        if lower.ends_with('b') && lower.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) {
            size = Some(p.to_string());
            continue;
        }
        if format.is_none()
            && !lower.contains('(')
            && lower.chars().all(|c| c.is_ascii_alphanumeric())
        {
            format = Some(p.to_string());
        }
    }
    (language, format, size, year)
}

pub fn parse_search_aa(html: &str, base: &str) -> (Vec<SearchResult>, bool) {
    let unhidden = html.replace("<!--", "").replace("-->", "");
    let doc = Html::parse_document(&unhidden);
    let mut out: Vec<SearchResult> = Vec::new();

    // Primary: iterate real AA cards. Each card carries two /md5/ anchors —
    // the cover-only one and the title one (which has text).
    for card in doc.select(&S_CARD) {
        let title_link = match card
            .select(&S_AA_LINK)
            .find(|a| !text(a).is_empty())
        {
            Some(a) => a,
            None => continue,
        };
        let href = match attr(&title_link, "href") {
            Some(h) => h,
            None => continue,
        };
        let id = match md5_from_href(&href) {
            Some(id) => id,
            None => continue,
        };
        if out.iter().any(|r| r.id == id) {
            continue;
        }

        let title = text(&title_link);
        let url = join_url(base, &href);
        let cover = card
            .select(&S_AA_IMG)
            .next()
            .and_then(|img| attr(&img, "src"))
            .filter(|s| !s.starts_with("data:"))
            .map(|s| join_url(base, &s));

        // Author / publisher: /search?q= anchors distinguished by icon span.
        let mut author: Option<String> = None;
        let mut publisher: Option<String> = None;
        for a in card.select(&S_SEARCH_LINK) {
            let icon = a
                .select(&S_SPAN)
                .next()
                .and_then(|s| s.value().attr("class"))
                .unwrap_or("");
            let content = text(&a);
            if content.is_empty() {
                continue;
            }
            if icon.contains("user-edit") {
                if author.is_none() { author = Some(content); }
            } else if icon.contains("company") {
                if publisher.is_none() { publisher = Some(content); }
            }
        }

        // Top row: "English [en] · MOBI · 35.5MB · 2019 · Book (fiction) · /zlib".
        let top_row = card
            .select(&S_TOPROW)
            .map(|d| text(&d))
            .find(|t| t.contains('·') || t.contains('['))
            .unwrap_or_default();

        let (mut language, mut format, mut size, mut year) = split_top_row(&top_row);

        // Fallback: publisher often carries the year ("Allen & Unwin, 2019").
        if year.is_none() {
            if let Some(p) = publisher.as_deref() {
                if let Some(idx) = p.rfind(", 20") {
                    let y = &p[idx + 2..];
                    if y.len() == 4 && y.chars().all(|c| c.is_ascii_digit()) {
                        year = Some(y.to_string());
                        publisher = Some(p[..idx].trim().to_string());
                    }
                }
            }
        }
        let _ = (&mut language, &mut format);

        let authors: Vec<String> = author
            .map(|a| {
                a.split('&')
                    .map(|s| clean(s))
                    .filter(|s| !s.is_empty())
                    .collect()
            })
            .unwrap_or_default();

        out.push(SearchResult {
            id,
            title,
            authors,
            publisher,
            year,
            language,
            format,
            rating: None,
            quality: None,
            cover,
            url,
            isbn: None,
            size,
        });
    }

    if out.is_empty() {
        // Fallback: older/unknown markup — anchors carrying titles directly.
        return parse_search_aa_legacy(&unhidden, base);
    }

    // Pagination: any link to a different page of the same search.
    let has_more = doc
        .select(&S_ALL_A)
        .filter_map(|a| attr(&a, "href"))
        .any(|h| h.contains("page=") && !h.ends_with("page=1"));
    (out, has_more)
}

fn parse_search_aa_legacy(html: &str, base: &str) -> (Vec<SearchResult>, bool) {
    let doc = Html::parse_document(html);
    let mut out: Vec<SearchResult> = Vec::new();

    for link in doc.select(&S_AA_LINK) {
        let href = match attr(&link, "href") {
            Some(h) => h,
            None => continue,
        };
        let id = match md5_from_href(&href) {
            Some(id) => id,
            None => continue,
        };
        if out.iter().any(|r| r.id == id) {
            continue;
        }
        let url = join_url(base, &href);

        let title = link
            .select(&S_AA_H3)
            .next()
            .map(|e| text(&e))
            .filter(|t| !t.is_empty());
        // Header links (e.g. the "Recent downloads" ticker) carry no title —
        // they are not results.
        let title = match title {
            Some(t) => t,
            None => continue,
        };

        let mut top_row = String::new();
        let mut publisher: Option<String> = None;
        let mut author: Option<String> = None;
        for div in link.select(&S_AA_DIV) {
            let content = text(&div);
            if content.is_empty() { continue; }
            if div.select(&S_AA_H3).next().is_some() { continue; }
            let classes = div.value().classes().collect::<Vec<_>>().join(" ");
            if classes.contains("italic") {
                if author.is_none() { author = Some(content); }
            } else if classes.contains("truncate") {
                if publisher.is_none() { publisher = Some(content); }
            } else if classes.contains("line-clamp") || (classes.is_empty() && top_row.is_empty()) {
                if top_row.is_empty() { top_row = content; }
            }
        }

        let cover = link
            .select(&S_AA_IMG)
            .next()
            .and_then(|img| attr(&img, "src"))
            .filter(|s| !s.starts_with("data:"))
            .map(|s| join_url(base, &s));

        let (language, format, size, year) = split_top_row(&top_row);
        let authors: Vec<String> = author
            .map(|a| {
                a.split('&')
                    .map(|s| clean(s))
                    .filter(|s| !s.is_empty())
                    .collect()
            })
            .unwrap_or_default();

        out.push(SearchResult {
            id,
            title,
            authors,
            publisher,
            year,
            language,
            format,
            rating: None,
            quality: None,
            cover,
            url,
            isbn: None,
            size,
        });
    }

    let has_more = doc
        .select(&S_ALL_A)
        .filter_map(|a| attr(&a, "href"))
        .any(|h| h.contains("page=") && !h.ends_with("page=1"));
    (out, has_more)
}

/// Parse a real Anna's Archive /md5/<hash> HTML page (the JSON endpoint is
/// not exposed on the .gl deployment, so the page itself is the source).
///
/// Structure (site template page/aarecord.html):
///   <div class="mb-4 p-6 …">            ← top box
///     <img src=cover>                   ← cover
///     <div class="text-sm text-gray-500">top_row ("English [en] · MOBI · …")</div>
///     <div class="text-3xl font-bold">TITLE</div>
///     <div class="text-md">PUBLISHER / EDITION</div>
///     <div class="italic">AUTHOR</div>
///     <div class="js-md5-top-box-description">description fields</div>
///   </div>
///   <div id="md5-panel-downloads">
///     <li>… <a class="js-download-link" href="URL">LABEL</a> extra…</li>
///     slow links: href starts with /slow_download/
///     external:   <ul class="… js-show-external …"> wraps them
///     fast:       everything else (partner mirror)
pub fn parse_book_aa_html(html: &str, base: &str, source_url: &str) -> Option<Book> {
    let doc = Html::parse_document(html);
    let top_box = doc.select(&S_BOOK_TOPBOX).next()?;

    // Title: `div.text-2xl` carries the title text plus a trailing 🔍 search
    // link wrapped in a span — cut everything from the first inline element.
    let title = top_box
        .select(&S_BOOK_TITLE)
        .next()
        .map(|e| fragment_text(&e.inner_html(), &["<span", "<a ", "<script"]))
        .filter(|t| !t.is_empty())
        .unwrap_or_else(|| "Untitled".to_string());

    // Author / publisher: /search?q= anchors distinguished by icon span,
    // same anatomy as the search cards. Publisher often ends with ", 2019".
    let mut author: Option<String> = None;
    let mut publisher_field: Option<String> = None;
    let mut year: Option<String> = None;
    for a in top_box.select(&S_SEARCH_LINK) {
        let icon = a
            .select(&S_SPAN)
            .next()
            .and_then(|s| s.value().attr("class"))
            .unwrap_or("");
        let content = text(&a);
        if content.is_empty() {
            continue;
        }
        if icon.contains("user-edit") {
            if author.is_none() {
                author = Some(content);
            }
        } else if icon.contains("company") {
            if publisher_field.is_none() {
                publisher_field = Some(content);
            }
        }
    }
    let authors: Vec<String> = author
        .as_deref()
        .unwrap_or("")
        .split([';', ','])
        .map(|s| clean(s))
        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("unknown"))
        .collect();

    let cover = top_box
        .select(&S_AA_IMG)
        .next()
        .and_then(|img| attr(&img, "src"))
        .filter(|s| !s.starts_with("data:"))
        .map(|s| join_url(base, &s));

    // Description: js-md5-top-box-description holds a small "description"
    // label plus the text itself in the first child div.mb-1.
    let description = top_box
        .select(&S_BOOK_DESC)
        .next()
        .map(|e| {
            e.select(&S_MB1)
                .map(|d| text(&d))
                .find(|t| t.len() > 10)
                .unwrap_or_else(|| text(&e))
        })
        .filter(|t| t.len() > 10);

    // Top row: "English [en] · MOBI · 35.5MB · 2019 · Book (fiction) · /zlib"
    // followed by a Save link and inline scripts — cut at the first <a>/<script>.
    let top_row = top_box
        .select(&S_BOOK_TOPROW)
        .map(|e| fragment_text(&e.inner_html(), &["<a ", "<script", "<span"]))
        .find(|t| t.contains('·') || t.contains('['))
        .unwrap_or_default();
    let (language, format, size, top_row_year) = split_top_row(&top_row);

    // Fallback: publisher often carries the year ("Allen & Unwin, 2019").
    if publisher_field.is_none() || year.is_none() {
        if let Some(p) = publisher_field.as_deref() {
            if let Some(idx) = p.rfind(", 20") {
                let y = &p[idx + 2..];
                if y.len() == 4 && y.chars().all(|c| c.is_ascii_digit()) {
                    if year.is_none() {
                        year = Some(y.to_string());
                    }
                    publisher_field = Some(p[..idx].trim().to_string());
                }
            }
        }
    }
    if year.is_none() {
        year = top_row_year;
    }

    // Metadata table rows.
    let mut metadata: Vec<MetaRow> = Vec::new();
    let mut push_row = |label: &str, value: Option<String>| {
        if let Some(val) = value {
            if !val.is_empty() && !metadata.iter().any(|m| m.label == label) {
                metadata.push(MetaRow { label: label.to_string(), value: val });
            }
        }
    };
    push_row("Publisher", publisher_field.clone());
    push_row("Author", author.clone());
    push_row("Language", language.clone());
    push_row("Format", format.clone().map(|f| f.to_uppercase()));
    push_row("Size", size.clone());
    push_row("Year", year.clone());
    // md5 from the source url, for reference.
    if let Some(md5) = md5_from_href(source_url) {
        push_row("MD5", Some(md5));
    }

    // Downloads: every .js-download-link, classified by href / parent list.
    let mut downloads: Vec<LinkRow> = Vec::new();
    for a in doc.select(&S_BOOK_DLNK) {
        let href = match attr(&a, "href") {
            Some(h) => h,
            None => continue,
        };
        let label = text(&a);
        let mut kind = "fast".to_string();
        if href.contains("/slow_download/") {
            kind = "slow".to_string();
        } else if href.contains("/fast_download_not_member")
            || href.contains("/torrents")
            || href.contains('#')
        {
            // Not a file link (torrent page, member notice, in-page anchor).
            kind = "external".to_string();
        } else if !href.starts_with('/') {
            // External mirrors live in a js-show-external list.
            let mut node = a.parent();
            loop {
                match node {
                    Some(n) => {
                        let is_external = n
                            .value()
                            .as_element()
                            .and_then(|el| el.attr("class"))
                            .map(|c| c.split_whitespace().any(|w| w == "js-show-external"))
                            .unwrap_or(false);
                        if is_external {
                            kind = "external".to_string();
                            break;
                        }
                        node = n.parent();
                    }
                    None => break,
                }
            }
        }
        let url = join_url(base, &href);
        if downloads.iter().any(|d| d.url == url) {
            continue;
        }
        downloads.push(LinkRow {
            label: if label.is_empty() { kind.clone() } else { label },
            url,
            kind,
            note: None,
        });
    }

    let id = md5_from_href(source_url).unwrap_or_default();

    Some(Book {
        id,
        title,
        authors,
        cover,
        description,
        rating: None,
        quality: None,
        comments: None,
        request_code: None,
        metadata,
        downloads,
        actions: Vec::new(),
        source_url: source_url.to_string(),
        base_url: base.to_string(),
    })
}

/// Parse the structured JSON from /db/aarecord/<id>.json into a Book.
pub fn parse_book_aa_json(json: &str, base: &str, source_url: &str) -> Option<Book> {
    let v: serde_json::Value = serde_json::from_str(json).ok()?;
    let id_full = v.get("id")?.as_str()?.to_string();
    let id = id_full.strip_prefix("md5:").unwrap_or(&id_full).to_string();
    let fu = v.get("file_unified_data")?;
    let additional = v.get("additional")?;
    let top_box = additional.get("top_box")?;

    let title = top_box
        .get("title")
        .and_then(|x| x.as_str())
        .map(|s| clean(s))
        .filter(|s| !s.is_empty())
        .or_else(|| fu.get("title_best").and_then(|x| x.as_str()).map(|s| clean(s)))
        .unwrap_or_else(|| "Untitled".to_string());

    let author_raw = top_box
        .get("author")
        .and_then(|x| x.as_str())
        .map(|s| clean(s))
        .unwrap_or_default();
    let authors: Vec<String> = author_raw
        .split([';', ','])
        .map(|s| clean(s))
        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("unknown"))
        .collect();

    let cover = top_box
        .get("cover_url")
        .and_then(|x| x.as_str())
        .filter(|s| !s.is_empty())
        .map(|s| join_url(base, s));

    let description = fu
        .get("stripped_description_best")
        .and_then(|x| x.as_str())
        .map(|s| clean(s))
        .filter(|s| s.len() > 10);

    let comments = fu
        .get("comments_best")
        .and_then(|x| x.as_str())
        .map(|s| clean(s))
        .filter(|s| !s.is_empty());

    // Metadata table rows.
    let mut metadata: Vec<MetaRow> = Vec::new();
    let mut push_row = |label: &str, value: Option<String>| {
        if let Some(val) = value {
            if !val.is_empty() && !metadata.iter().any(|m| m.label == label) {
                metadata.push(MetaRow { label: label.to_string(), value: val });
            }
        }
    };
    let fu_str = |key: &str| {
        fu.get(key)
            .and_then(|x| x.as_str())
            .map(|s| clean(s).to_string())
            .filter(|s| !s.is_empty())
    };
    push_row("Publisher", fu_str("publisher_best"));
    push_row("Edition", fu_str("edition_varia_best"));
    push_row(
        "Language",
        fu.get("most_likely_language_codes")
            .and_then(|x| x.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|c| c.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            }),
    );
    push_row(
        "Format",
        fu_str("extension_best").map(|s| format!(".{}", s.trim_start_matches('.'))),
    );
    push_row("Content type", fu_str("content_type_best"));
    push_row("Original filename", fu_str("original_filename_best"));
    push_row("Added", fu_str("added_date_best"));
    push_row(
        "ISBN",
        fu.get("identifiers_unified")
            .and_then(|x| x.get("isbn13"))
            .and_then(|x| x.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|c| c.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            }),
    );
    push_row(
        "DOI",
        fu.get("identifiers_unified")
            .and_then(|x| x.get("doi"))
            .and_then(|x| x.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|c| c.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            }),
    );

    // Downloads: fast (members), slow (free with wait), external mirrors.
    let mut downloads: Vec<LinkRow> = Vec::new();
    for (key, kind) in [
        ("fast_partner_urls", "fast"),
        ("slow_partner_urls", "slow"),
        ("download_urls", "external"),
    ] {
        if let Some(arr) = additional.get(key).and_then(|x| x.as_array()) {
            for entry in arr {
                let parts = match entry.as_array() {
                    Some(p) => p,
                    None => continue,
                };
                let label = parts
                    .get(0)
                    .and_then(|x| x.as_str())
                    .map(|s| strip_html(s))
                    .unwrap_or_default();
                let url = parts
                    .get(1)
                    .and_then(|x| x.as_str())
                    .unwrap_or_default()
                    .to_string();
                let extra = parts
                    .get(2)
                    .and_then(|x| x.as_str())
                    .map(|s| strip_html(s))
                    .unwrap_or_default();
                if url.is_empty() { continue; }
                let url = join_url(base, &url);
                if downloads.iter().any(|d| d.url == url) { continue; }
                downloads.push(LinkRow {
                    label: if label.is_empty() { kind.to_string() } else { label },
                    url,
                    kind: kind.to_string(),
                    note: if extra.is_empty() { None } else { Some(extra) },
                });
            }
        }
    }

    Some(Book {
        id,
        title,
        authors,
        cover,
        description,
        rating: None,
        quality: None,
        comments,
        request_code: None,
        metadata,
        downloads,
        actions: Vec::new(),
        source_url: source_url.to_string(),
        base_url: base.to_string(),
    })
}
