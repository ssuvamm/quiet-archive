use once_cell::sync::Lazy;
use scraper::{ElementRef, Html, Selector};

use crate::model::{Book, LinkRow, MetaRow, SearchResult};
use crate::util::{clean, extension_of, is_document_ext, is_image_ext, join_url, origin};

fn sel(css: &str) -> Selector {
    Selector::parse(css).expect("valid selector")
}

static S_BOOK_CARD: Lazy<Selector> = Lazy::new(|| sel("div.resItemBoxBooks"));
static S_ANY_CARD: Lazy<Selector> = Lazy::new(|| sel("div.resItemBox"));
static S_AA_CARD: Lazy<Selector> = Lazy::new(|| sel("div.js-aarecord-list-outer > div"));
static S_TITLE_LINK: Lazy<Selector> = Lazy::new(|| sel("h3[itemprop=\"name\"] a"));
static S_H3: Lazy<Selector> = Lazy::new(|| sel("h3"));
static S_AUTHOR: Lazy<Selector> = Lazy::new(|| sel("a[itemprop=\"author\"]"));
static S_PUBLISHER: Lazy<Selector> = Lazy::new(|| sel("a[itemprop=\"publisher\"]"));
static S_COVER: Lazy<Selector> = Lazy::new(|| sel("img.cover, img[itemprop=\"image\"]"));
static S_PROP: Lazy<Selector> = Lazy::new(|| sel("div.bookProperty"));
static S_PROP_LABEL: Lazy<Selector> = Lazy::new(|| sel(".property_label"));
static S_PROP_VALUE: Lazy<Selector> = Lazy::new(|| sel(".property_value"));
static S_RATING: Lazy<Selector> = Lazy::new(|| sel(".book-rating-interest-score"));
static S_QUALITY: Lazy<Selector> = Lazy::new(|| sel(".book-rating-quality-score"));
static S_H1: Lazy<Selector> = Lazy::new(|| sel("h1[itemprop=\"name\"], h1"));
static S_BOOK_COVER: Lazy<Selector> = Lazy::new(|| {
    sel("img[itemprop=\"image\"], .details-book-cover-content img, .z-book-cover img, img.cover")
});
static S_DESC: Lazy<Selector> = Lazy::new(|| sel("#bookDescriptionBox, div[itemprop=\"reviewBody\"]"));
static S_COMMENTS: Lazy<Selector> = Lazy::new(|| sel(".book-comments-info"));
static S_REQCODE: Lazy<Selector> = Lazy::new(|| sel("#reqcode"));
static S_BTN: Lazy<Selector> = Lazy::new(|| sel("a.btn, a.dlButton"));
static S_ALL_A: Lazy<Selector> = Lazy::new(|| sel("a[href]"));
static S_IMG: Lazy<Selector> = Lazy::new(|| sel("img"));
static S_NEXT: Lazy<Selector> =
    Lazy::new(|| sel("a[rel=\"next\"], .pagination a.next, a[href*=\"page=\"]"));
static S_ISBN: Lazy<Selector> = Lazy::new(|| sel("[itemprop=\"isbn\"]"));

fn text(el: &ElementRef) -> String {
    clean(&el.text().collect::<Vec<_>>().join(" "))
}

// --- Anna's Archive (annas-archive.gl) selectors live in scrape_aa.rs -------

fn attr(el: &ElementRef, name: &str) -> Option<String> {
    el.value().attr(name).map(|v| clean(v)).filter(|v| !v.is_empty())
}

fn first_text(root: &ElementRef, selector: &Selector) -> Option<String> {
    root.select(selector)
        .next()
        .map(|e| text(&e))
        .filter(|t| !t.is_empty())
}

fn prop_value(root: &ElementRef, class: &str) -> Option<String> {
    let selector = format!("div.{} .property_value", class);
    let parsed = Selector::parse(&selector).ok()?;
    root.select(&parsed)
        .next()
        .map(|e| text(&e))
        .filter(|t| !t.is_empty())
}

fn id_from_url(url: &str) -> String {
    let base = url.split(['?', '#']).next().unwrap_or(url);
    let tail = base.trim_end_matches('/').rsplit('/').next().unwrap_or("");
    if let Some(rest) = base.split("/book/").nth(1) {
        return rest.trim_end_matches('/').to_string();
    }
    if let Some(rest) = base.split("/md5/").nth(1) {
        return rest.trim_end_matches('/').to_string();
    }
    tail.to_string()
}

fn cover_of(card: &ElementRef, base: &str) -> Option<String> {
    let img = card.select(&S_COVER).next()?;
    let raw = attr(&img, "data-src")
        .or_else(|| attr(&img, "data-srcset").map(|s| s.split(',').next().unwrap_or("").split(' ').next().unwrap_or("").to_string()))
        .or_else(|| attr(&img, "src"))?;
    if raw.starts_with("data:") || raw.is_empty() {
        return None;
    }
    Some(join_url(base, &raw))
}

pub fn parse_search(html: &str, base: &str) -> (Vec<SearchResult>, bool) {
    let doc = Html::parse_document(html);
    let mut cards: Vec<ElementRef> = doc.select(&S_BOOK_CARD).collect();
    if cards.is_empty() {
        cards = doc.select(&S_ANY_CARD).collect();
    }
    if cards.is_empty() {
        cards = doc
            .select(&S_AA_CARD)
            .filter(|c| c.select(&S_H3).next().is_some() || c.select(&S_TITLE_LINK).next().is_some())
            .collect();
    }

    let mut out = Vec::new();
    for card in cards {
        let title_link = card.select(&S_TITLE_LINK).next();
        let any_link = card.select(&S_ALL_A).next();
        let link = title_link.or(any_link);
        let href = link.and_then(|l| attr(&l, "href")).unwrap_or_default();
        if href.starts_with("javascript") {
            continue;
        }
        let url = if href.is_empty() {
            String::new()
        } else {
            join_url(base, &href)
        };
        let mut id = attr(&card, "data-book_id").unwrap_or_default();
        if id.is_empty() {
            id = id_from_url(&url);
        }
        if id.is_empty() || url.is_empty() {
            continue;
        }
        let title = link
            .map(|l| text(&l))
            .filter(|t| !t.is_empty())
            .or_else(|| first_text(&card, &S_H3))
            .unwrap_or_else(|| "Untitled".to_string());

        let authors: Vec<String> = card
            .select(&S_AUTHOR)
            .map(|a| text(&a))
            .filter(|a| !a.is_empty())
            .collect();

        let publisher = card
            .select(&S_PUBLISHER)
            .next()
            .map(|e| text(&e))
            .filter(|t| !t.is_empty())
            .or_else(|| prop_value(&card, "property_publisher"));

        let year = prop_value(&card, "property_year");
        let language = prop_value(&card, "property_language");
        let format = prop_value(&card, "property__file").or_else(|| prop_value(&card, "property_file"));
        let rating = card.select(&S_RATING).next().map(|e| text(&e)).filter(|t| !t.is_empty());
        let quality = card.select(&S_QUALITY).next().map(|e| text(&e)).filter(|t| !t.is_empty());
        let isbn = card
            .select(&S_ISBN)
            .next()
            .map(|e| text(&e))
            .filter(|t| !t.is_empty())
            .or_else(|| attr(&card, "data-isbn"))
            .filter(|t| !t.is_empty());
        let size = prop_value(&card, "property_size");

        out.push(SearchResult {
            id,
            title,
            authors,
            publisher,
            year,
            language,
            format,
            rating,
            quality,
            cover: cover_of(&card, base),
            url,
            isbn,
            size,
        });
    }

    let has_more = doc.select(&S_NEXT).next().is_some();
    (out, has_more)
}

fn classify_download(href: &str, base: &str) -> Option<(&'static str, String)> {
    let lower = href.to_lowercase();
    if lower.starts_with("javascript:") || lower.starts_with("mailto:") || lower == "#" {
        return None;
    }
    let abs = join_url(base, href);
    let path = abs
        .split(['?', '#'])
        .next()
        .unwrap_or(&abs)
        .to_lowercase();
    if path.contains("/slow_download") {
        return Some(("slow", abs));
    }
    if path.contains("/fast_download") {
        return Some(("fast", abs));
    }
    if path.contains("/ipfs") {
        return Some(("ipfs", abs));
    }
    let ext = extension_of(&path, None);
    if is_document_ext(&ext) && !is_image_ext(&ext) {
        return Some(("direct", abs));
    }
    let same_host = origin(&abs) == origin(base);
    if same_host && (path.contains("/download") || path.contains("/dl/")) {
        return Some(("direct", abs));
    }
    None
}

pub fn parse_book(html: &str, base: &str, source_url: &str) -> Book {
    let doc = Html::parse_document(html);
    let root = doc.root_element();

    let title = doc
        .select(&S_H1)
        .next()
        .map(|e| text(&e))
        .filter(|t| !t.is_empty())
        .unwrap_or_else(|| "Untitled".to_string());

    let mut authors: Vec<String> = Vec::new();
    for a in doc.select(&S_AUTHOR) {
        let name = text(&a);
        if !name.is_empty() && !authors.contains(&name) {
            authors.push(name);
        }
    }
    if authors.is_empty() {
        // Fallback: the italic line right under the h1 on the mirror layout.
        if let Some(i) = doc.select(&sel("i")).next() {
            if let Some(a) = i.select(&S_ALL_A).next() {
                let name = text(&a);
                if !name.is_empty() {
                    authors.push(name);
                }
            }
        }
    }

    let cover = doc
        .select(&S_BOOK_COVER)
        .next()
        .and_then(|img| {
            attr(&img, "data-src")
                .or_else(|| attr(&img, "src"))
                .filter(|s| !s.starts_with("data:"))
        })
        .map(|s| join_url(base, &s));

    let description = doc
        .select(&S_DESC)
        .next()
        .map(|e| clean(&e.text().collect::<Vec<_>>().join(" ")))
        .filter(|t| !t.is_empty())
        .filter(|t| t.len() > 3);

    let rating = doc.select(&S_RATING).next().map(|e| text(&e)).filter(|t| !t.is_empty());
    let quality = doc.select(&S_QUALITY).next().map(|e| text(&e)).filter(|t| !t.is_empty());
    let comments = doc
        .select(&S_COMMENTS)
        .next()
        .map(|e| text(&e))
        .filter(|t| !t.is_empty());
    let request_code = doc
        .select(&S_REQCODE)
        .next()
        .map(|e| text(&e))
        .filter(|t| !t.is_empty());

    let mut metadata: Vec<MetaRow> = Vec::new();
    for prop in doc.select(&S_PROP) {
        let label = prop
            .select(&S_PROP_LABEL)
            .next()
            .map(|e| text(&e).trim_end_matches(':').to_string())
            .unwrap_or_default();
        let value = prop
            .select(&S_PROP_VALUE)
            .next()
            .map(|e| text(&e))
            .unwrap_or_default();
        if label.is_empty() || value.is_empty() {
            continue;
        }
        if label.eq_ignore_ascii_case("your tags") || label.eq_ignore_ascii_case("categories") && value.is_empty() {
            continue;
        }
        if metadata.iter().any(|m| m.label == label) {
            continue;
        }
        metadata.push(MetaRow { label, value });
    }

    let mut downloads: Vec<LinkRow> = Vec::new();
    let mut actions: Vec<LinkRow> = Vec::new();
    let base_host = origin(base);
    for a in doc.select(&S_ALL_A) {
        let href = match attr(&a, "href") {
            Some(h) => h,
            None => continue,
        };
        let label = text(&a);
        if let Some((kind, url)) = classify_download(&href, base) {
            if downloads.iter().any(|d| d.url == url) {
                continue;
            }
            let fallback = url
                .split(['?', '#'])
                .next()
                .unwrap_or(&url)
                .rsplit('/')
                .next()
                .unwrap_or("File");
            downloads.push(LinkRow {
                label: if label.is_empty() {
                    fallback.to_string()
                } else {
                    label
                },
                url,
                kind: kind.to_string(),
                note: None,
            });
        } else if href.starts_with("http") {
            let abs = join_url(base, &href);
            if origin(&abs) == base_host || label.is_empty() || label.len() > 60 {
                continue;
            }
            let is_button = a.value().classes().any(|c| {
                c.contains("btn") || c.contains("dlButton") || c.contains("menu__link")
            });
            if !is_button {
                continue;
            }
            if actions.iter().any(|x| x.url == abs) {
                continue;
            }
            actions.push(LinkRow {
                label,
                url: abs,
                kind: "external".to_string(),
                note: None,
            });
            if actions.len() >= 8 {
                break;
            }
        }
    }

    let _ = root;
    Book {
        id: id_from_url(source_url),
        title,
        authors,
        cover,
        description,
        rating,
        quality,
        comments,
        request_code,
        metadata,
        downloads,
        actions,
        source_url: source_url.to_string(),
        base_url: base.to_string(),
    }
}
