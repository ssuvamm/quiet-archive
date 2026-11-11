use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct SearchResult {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub authors: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub publisher: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub year: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub language: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub format: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rating: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub quality: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cover: Option<String>,
    pub url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub isbn: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub size: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct SearchPage {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    pub query: String,
    pub page: u32,
    pub has_more: bool,
    pub results: Vec<SearchResult>,
    pub took_ms: u64,
    pub source: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MetaRow {
    pub label: String,
    pub value: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct LinkRow {
    pub label: String,
    pub url: String,
    /// "slow" | "fast" | "ipfs" | "direct" | "external"
    pub kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Book {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub authors: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cover: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rating: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub quality: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub comments: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_code: Option<String>,
    #[serde(default)]
    pub metadata: Vec<MetaRow>,
    #[serde(default)]
    pub downloads: Vec<LinkRow>,
    #[serde(default)]
    pub actions: Vec<LinkRow>,
    pub source_url: String,
    pub base_url: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct BookResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub book: Option<Book>,
    pub took_ms: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct DownloadRequest {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub authors: Vec<String>,
    #[serde(default)]
    pub format: String,
    pub url: String,
    #[serde(default)]
    pub cover: Option<String>,
    #[serde(default)]
    pub book_url: Option<String>,
    #[serde(default)]
    pub replace: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DownloadProgress {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub authors: Vec<String>,
    pub state: String,
    pub received: u64,
    pub total: u64,
    pub speed: u64,
    pub format: String,
    pub filename: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temp_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    pub created_at: i64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct LibraryItem {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub authors: Vec<String>,
    pub format: String,
    pub size: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cover: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub book_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uri: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub local_path: Option<String>,
    pub filename: String,
    pub mime: String,
    pub saved_at: i64,
    #[serde(default)]
    pub read: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct LibraryStore {
    pub items: Vec<LibraryItem>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct OkResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub item: Option<LibraryItem>,
}
