use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(default)]
pub struct Card {
    pub id: i64,
    pub title: String,
    pub cover: String,
    pub dur: String,
    pub score: String,
    pub views: String,
    pub author: String,
    pub time: String,
    /// Landscape artwork (16:9 still frame) vs portrait DVD cover. The two
    /// templates serve wildly different aspect ratios (640x360 vs 268x394), so
    /// the grid has to pick a shape per card instead of one global ratio.
    pub wide: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(default)]
pub struct Section {
    pub title: String,
    pub more: String,
    pub items: Vec<Card>,
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(default)]
pub struct Source {
    pub quality: u32,
    pub url: String,
    pub size: u64,
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(default)]
pub struct Detail {
    pub id: i64,
    pub title: String,
    /// Localized (Chinese) title shown on the description panel. Often equals
    /// `title` for unlocalized uploads, so the UI only shows it as a subtitle
    /// when the two differ.
    pub cn_title: String,
    pub cover: String,
    pub poster: String,
    pub duration: u32,
    pub views: String,
    pub date: String,
    pub author: String,
    pub genre: String,
    pub like_pct: String,
    pub like_count: String,
    pub tags: Vec<String>,
    pub desc: String,
    pub sources: Vec<Source>,
    pub related: Vec<Card>,
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(default)]
pub struct ListPage {
    pub items: Vec<Card>,
    pub page: u32,
    pub max_page: u32,
    pub has_next: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(default)]
pub struct DownloadTask {
    pub id: i64,
    pub title: String,
    pub cover: String,
    pub quality: u32,
    pub path: String,
    pub total: u64,
    pub done: u64,
    pub state: String, // queued | running | paused | done | error
    pub error: String,
}
