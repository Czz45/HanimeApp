//! Public surface of the Rust core. Everything the Kotlin UI needs is exposed
//! here as plain functions returning JSON strings, so the JNI layer stays a thin
//! string-in/string-out shim and the same code is testable from a desktop CLI.

pub mod html;
pub mod model;
pub mod net;
pub mod tags;
pub mod translate;

#[cfg(feature = "jni-bridge")]
pub mod jni;

use model::*;
use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};

pub const BASE: &str = "https://hanime1.me";

// ---------------------------------------------------------------- settings

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone)]
#[serde(default)]
pub struct Settings {
    pub proxy: String,
    pub hide_watched: bool,
    pub preferred_quality: u32,
    pub storage_dir: String,
    pub auto_play: bool,
    /// How much timeline a full-width horizontal drag covers, in seconds.
    ///
    /// The gesture maps finger travel to a delta proportional to the duration, so
    /// a 25-minute episode used to jump several minutes per swipe. This is the
    /// "seconds for a full screen width" knob so slower dragging is available.
    pub seek_seconds: u32,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            proxy: String::new(),
            hide_watched: true,
            preferred_quality: 1080,
            storage_dir: String::new(),
            auto_play: false,
            seek_seconds: 90,
        }
    }
}

fn state_dir() -> PathBuf {
    // Set by the Android layer to the app's files dir; desktop tests get a
    // local folder so the same code paths run without JNI.
    match std::env::var("HANIME_STATE_DIR") {
        Ok(v) if !v.is_empty() => PathBuf::from(v),
        _ => std::env::temp_dir().join("hanime-core"),
    }
}

fn settings_path() -> PathBuf {
    state_dir().join("settings.json")
}

fn settings_cache() -> &'static Mutex<Settings> {
    static S: OnceLock<Mutex<Settings>> = OnceLock::new();
    S.get_or_init(|| Mutex::new(load_settings()))
}

fn load_settings() -> Settings {
    std::fs::read_to_string(settings_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

/// Proxy discovered from the platform (Android system HTTP proxy).
///
/// Mobile networks with a captive proxy are common and the user should not have to
/// retype it, so a proxy entered in Settings always wins over this fallback.
/// Function-local statics are separate per function, so this one lives at module
/// scope: both the setter and the getter must observe the same cell.
static SYSTEM_PROXY: OnceLock<Mutex<String>> = OnceLock::new();

fn system_proxy_cell() -> &'static Mutex<String> {
    SYSTEM_PROXY.get_or_init(|| Mutex::new(String::new()))
}

pub fn set_system_proxy(value: &str) {
    *system_proxy_cell().lock().unwrap() = value.trim().to_string();
    // Force the HTTP agent to pick the new route on the next request.
    net::invalidate_agent();
}

pub fn system_proxy_value() -> Option<String> {
    let v = system_proxy_cell().lock().unwrap().clone();
    if v.is_empty() {
        None
    } else {
        Some(v)
    }
}

pub fn proxy_value() -> Option<String> {
    let s = settings_cache().lock().unwrap().proxy.clone();
    if s.trim().is_empty() {
        None
    } else {
        Some(s)
    }
}

pub fn set_settings_json(json: &str) -> String {
    match serde_json::from_str::<Settings>(json) {
        Ok(s) => {
            let mut g = settings_cache().lock().unwrap();
            *g = s.clone();
            drop(g);
            let _ = std::fs::create_dir_all(state_dir());
            let _ = std::fs::write(
                settings_path(),
                serde_json::to_string_pretty(&s).unwrap_or_default(),
            );
            serde_json::to_string(&s).unwrap_or_default()
        }
        Err(e) => format!("{{\"error\":\"{e}\"}}"),
    }
}

pub fn get_settings_json() -> String {
    let s = settings_cache().lock().unwrap().clone();
    serde_json::to_string(&s).unwrap_or_default()
}

// ------------------------------------------------------------ watched list

fn watched_path() -> PathBuf {
    state_dir().join("watched.json")
}

/// Insertion-ordered list plus a hash index.
///
/// The list is what gets persisted and handed to Kotlin (order matters for the
/// "recently watched" feel), the set is what every filter actually queries. The
/// site has no cap on how many works exist, so neither do we: capping at a few
/// thousand used to silently forget the oldest entries, which brought already
/// watched titles back into the feed. Keeping both structures means unbounded
/// history costs O(1) per lookup instead of a linear scan that grows forever.
struct Watched {
    list: Vec<i64>,
    set: HashSet<i64>,
}

static WATCHED: OnceLock<Mutex<Watched>> = OnceLock::new();

fn watched() -> &'static Mutex<Watched> {
    WATCHED.get_or_init(|| {
        let list: Vec<i64> = std::fs::read_to_string(watched_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        let set = list.iter().copied().collect();
        Mutex::new(Watched { list, set })
    })
}

pub fn mark_watched(id: i64) {
    if id <= 0 {
        return;
    }
    let mut g = watched().lock().unwrap();
    if !g.set.insert(id) {
        return;
    }
    g.list.push(id);
    let _ = std::fs::create_dir_all(state_dir());
    let _ = std::fs::write(
        watched_path(),
        serde_json::to_string(&g.list).unwrap_or_default(),
    );
}

pub fn is_watched(id: i64) -> bool {
    watched().lock().unwrap().set.contains(&id)
}

pub fn watched_json() -> String {
    serde_json::to_string(&watched().lock().unwrap().list).unwrap_or_else(|_| "[]".into())
}

pub fn set_watched_json(json: &str) {
    if let Ok(v) = serde_json::from_str::<Vec<i64>>(json) {
        let set = v.iter().copied().collect();
        *watched().lock().unwrap() = Watched { list: v, set };
        let _ = std::fs::create_dir_all(state_dir());
        let _ = std::fs::write(watched_path(), json);
    }
}

pub fn clear_watched() {
    let mut g = watched().lock().unwrap();
    g.list.clear();
    g.set.clear();
    let _ = std::fs::write(watched_path(), "[]");
}

// ---------------------------------------------------------------- favorites

/// Favourites are stored as whole cards, not just ids: the list has to render
/// (cover, title, duration) without a network round trip per entry, and the
/// detail page only needs the id anyway. They are independent of the download
/// library so bookmarking never costs disk space.
fn favorites_path() -> PathBuf {
    state_dir().join("favorites.json")
}

static FAVORITES: OnceLock<Mutex<Vec<Card>>> = OnceLock::new();

fn favorites() -> &'static Mutex<Vec<Card>> {
    FAVORITES.get_or_init(|| {
        let v: Vec<Card> = std::fs::read_to_string(favorites_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        Mutex::new(v)
    })
}

fn save_favorites(v: &[Card]) {
    let _ = std::fs::create_dir_all(state_dir());
    let _ = std::fs::write(favorites_path(), serde_json::to_string(v).unwrap_or_default());
}

pub fn favorites_json() -> String {
    serde_json::to_string(&*favorites().lock().unwrap()).unwrap_or_else(|_| "[]".into())
}

pub fn is_favorite(id: i64) -> bool {
    favorites().lock().unwrap().iter().any(|c| c.id == id)
}

/// Adds the card when absent and removes it when present; the caller passes the
/// card it already has on screen so no fetch is needed to bookmark it.
pub fn toggle_favorite(card_json: &str) -> Result<String, String> {
    let card: Card = serde_json::from_str(card_json).map_err(|e| e.to_string())?;
    if card.id <= 0 {
        return Err("card has no id".into());
    }
    let mut g = favorites().lock().unwrap();
    match g.iter().position(|c| c.id == card.id) {
        Some(i) => {
            g.remove(i);
        }
        // Newest first: the list is a "what do I want to come back to" queue.
        None => g.insert(0, card),
    }
    save_favorites(&g);
    Ok(serde_json::to_string(&*g).unwrap_or_else(|_| "[]".into()))
}

pub fn clear_favorites() {
    favorites().lock().unwrap().clear();
    save_favorites(&[]);
}

// ----------------------------------------------------------- reel sampling

/// Counter for every id the short-video feed has shown, keyed by id.
///
/// This exists so the settings page can plot the sampler's real shape instead of
/// the user guessing whether "random" is actually mixing. It doubles as the only
/// cheap sanity check we have: the 166k–401k band has no works at all, so any
/// count landing in that bucket means the sampler is reaching outside the
/// catalogue rather than picking a bad page.
fn reel_stats_path() -> PathBuf {
    state_dir().join("reel_stats.json")
}

#[derive(serde::Serialize, serde::Deserialize, Debug, Clone, Default)]
#[serde(default)]
struct ReelStats {
    /// id -> times shown. Stored as a map so a re-watch just bumps a counter.
    counts: HashMap<i64, u32>,
    /// genre -> times sampled, so the chart can say which feed was being played.
    genres: HashMap<String, u32>,
    total: u32,
}

static REEL_STATS: OnceLock<Mutex<ReelStats>> = OnceLock::new();

fn reel_stats() -> &'static Mutex<ReelStats> {
    REEL_STATS.get_or_init(|| {
        let s: ReelStats = std::fs::read_to_string(reel_stats_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        Mutex::new(s)
    })
}

fn save_reel_stats(s: &ReelStats) {
    let _ = std::fs::create_dir_all(state_dir());
    let _ = std::fs::write(reel_stats_path(), serde_json::to_string(s).unwrap_or_default());
}

/// Records one appearance of `id` in the feed.
///
/// Called from the UI when a page becomes current, not when it is prefetched —
/// otherwise the chart would count cards the user never saw.
pub fn note_reel(id: i64, genre: &str) {
    if id <= 0 {
        return;
    }
    let mut g = reel_stats().lock().unwrap();
    *g.counts.entry(id).or_insert(0) += 1;
    let key = if genre.is_empty() { "全部" } else { genre };
    *g.genres.entry(key.to_string()).or_insert(0) += 1;
    g.total += 1;
    // The chart only needs shape, not an unbounded log; 20k distinct ids is
    // already far more than anyone will scroll through in a session.
    if g.counts.len() > 20_000 {
        let mut v: Vec<(i64, u32)> = g.counts.drain().collect();
        v.sort_by(|a, b| b.1.cmp(&a.1));
        v.truncate(10_000);
        g.counts = v.into_iter().collect();
    }
    save_reel_stats(&g);
}

pub fn clear_reel_stats() {
    let mut g = reel_stats().lock().unwrap();
    *g = ReelStats::default();
    save_reel_stats(&g);
}

/// Fixed bucket edges over the id space.
///
/// Not evenly spaced on purpose: the catalogue is two dense clusters (a low band
/// up to ~166k and a high band from ~401k) separated by a quarter-million empty
/// ids. Equal-width buckets would put 99% of the samples in two bars and hide
/// everything worth seeing.
const REEL_BUCKETS: &[(i64, i64, &str)] = &[
    (10, 999, "10"),
    (1_000, 4_999, "1k"),
    (5_000, 19_999, "5k"),
    (20_000, 49_999, "20k"),
    (50_000, 99_999, "50k"),
    (100_000, 149_999, "100k"),
    (150_000, 166_774, "150k"),
    (166_775, 401_053, "空档"),
    (401_054, 404_999, "401k"),
    (405_000, 408_977, "405k"),
];

pub fn reel_stats_json() -> String {
    let g = reel_stats().lock().unwrap();
    let mut buckets = Vec::with_capacity(REEL_BUCKETS.len());
    for (lo, hi, label) in REEL_BUCKETS {
        let count: u32 = g
            .counts
            .iter()
            .filter(|(id, _)| **id >= *lo && **id <= *hi)
            .map(|(_, c)| *c)
            .sum();
        let distinct = g
            .counts
            .keys()
            .filter(|id| **id >= *lo && **id <= *hi)
            .count();
        buckets.push(serde_json::json!({
            "label": label,
            "lo": lo,
            "hi": hi,
            "count": count,
            "distinct": distinct,
        }));
    }

    let mut top: Vec<(i64, u32)> = g.counts.iter().map(|(k, v)| (*k, *v)).collect();
    top.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let top: Vec<_> = top
        .into_iter()
        .take(8)
        .map(|(id, count)| serde_json::json!({ "id": id, "count": count }))
        .collect();

    let mut genres: Vec<(String, u32)> = g.genres.iter().map(|(k, v)| (k.clone(), *v)).collect();
    genres.sort_by(|a, b| b.1.cmp(&a.1));
    let genres: Vec<_> = genres
        .into_iter()
        .map(|(name, count)| serde_json::json!({ "name": name, "count": count }))
        .collect();

    let min_id = g.counts.keys().min().copied().unwrap_or(0);
    let max_id = g.counts.keys().max().copied().unwrap_or(0);

    serde_json::json!({
        "total": g.total,
        "distinct": g.counts.len(),
        "min_id": min_id,
        "max_id": max_id,
        "buckets": buckets,
        "top": top,
        "genres": genres,
    })
    .to_string()
}

// ------------------------------------------------------------ library store

fn library_path() -> PathBuf {
    state_dir().join("library.json")
}

static LIBRARY: OnceLock<Mutex<Vec<DownloadTask>>> = OnceLock::new();

fn library() -> &'static Mutex<Vec<DownloadTask>> {
    LIBRARY.get_or_init(|| {
        let v: Vec<DownloadTask> = std::fs::read_to_string(library_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        Mutex::new(v)
    })
}

fn save_library() {
    let _ = std::fs::create_dir_all(state_dir());
    let v = library().lock().unwrap().clone();
    let _ = std::fs::write(
        library_path(),
        serde_json::to_string(&v).unwrap_or_default(),
    );
}

pub fn library_json() -> String {
    serde_json::to_string(&*library().lock().unwrap()).unwrap_or_else(|_| "[]".into())
}

// ------------------------------------------------------------- networking

pub fn url_for_list(genre: &str, sort: &str, date: &str, duration: &str, page: u32) -> String {
    let mut u = format!("{BASE}/search?");
    if !genre.is_empty() && genre != "全部" {
        u.push_str(&format!("genre={}&", net::percent_encode(genre)));
    }
    if !sort.is_empty() {
        u.push_str(&format!("sort={}&", net::percent_encode(sort)));
    }
    if !date.is_empty() {
        u.push_str(&format!("date={}&", net::percent_encode(date)));
    }
    if !duration.is_empty() {
        u.push_str(&format!("duration={}&", net::percent_encode(duration)));
    }
    u.push_str(&format!("page={page}"));
    u
}

fn filter_watched(mut page: ListPage) -> ListPage {
    let hide = settings_cache().lock().unwrap().hide_watched;
    if hide {
        let g = watched().lock().unwrap();
        page.items.retain(|c| !g.set.contains(&c.id));
    }
    page
}

pub fn fetch_home() -> Result<String, String> {
    let html = net::get(&format!("{BASE}/"))?;
    let mut sections = html::parse_home(&html)?;
    let hide = settings_cache().lock().unwrap().hide_watched;
    if hide {
        let g = watched().lock().unwrap();
        for s in &mut sections {
            s.items.retain(|c| !g.set.contains(&c.id));
        }
        sections.retain(|s| !s.items.is_empty());
    }
    serde_json::to_string(&sections).map_err(|e| e.to_string())
}

pub fn fetch_list(
    genre: &str,
    sort: &str,
    date: &str,
    duration: &str,
    page: u32,
) -> Result<String, String> {
    let url = url_for_list(genre, sort, date, duration, page);
    let html = net::get(&url)?;
    let lp = filter_watched(html::parse_list(&html, page)?);
    for c in &lp.items {
        note_id(c.id);
    }
    note_max_page(genre, lp.max_page);
    serde_json::to_string(&lp).map_err(|e| e.to_string())
}

pub fn fetch_search(query: &str, page: u32) -> Result<String, String> {
    fetch_search_filtered(query, "", "", "", "", &[], false, page)
}

/// `/search` with every filter the site exposes.
///
/// The search box and the genre/sort/date/duration/tag dropdowns all submit to
/// the same endpoint, and they compose — `query=櫻&genre=裏番&tags[]=無碼` is a
/// valid combination, so one function serves both the plain search field and the
/// filter sheet.
#[allow(clippy::too_many_arguments)]
pub fn fetch_search_filtered(
    query: &str,
    genre: &str,
    sort: &str,
    date: &str,
    duration: &str,
    tags: &[String],
    broad: bool,
    page: u32,
) -> Result<String, String> {
    let html = net::get(&url_for_search(
        query, genre, sort, date, duration, tags, broad, page,
    ))?;
    let lp = filter_watched(html::parse_list(&html, page)?);
    for c in &lp.items {
        note_id(c.id);
    }
    note_max_page(genre, lp.max_page);
    serde_json::to_string(&lp).map_err(|e| e.to_string())
}

#[allow(clippy::too_many_arguments)]
pub fn url_for_search(
    query: &str,
    genre: &str,
    sort: &str,
    date: &str,
    duration: &str,
    tags: &[String],
    broad: bool,
    page: u32,
) -> String {
    let mut u = format!("{BASE}/search?");
    if !query.trim().is_empty() {
        u.push_str(&format!("query={}&", net::percent_encode(query.trim())));
    }
    if !genre.is_empty() && genre != "全部" {
        u.push_str(&format!("genre={}&", net::percent_encode(genre)));
    }
    if !sort.is_empty() {
        u.push_str(&format!("sort={}&", net::percent_encode(sort)));
    }
    if !date.is_empty() {
        u.push_str(&format!("date={}&", net::percent_encode(date)));
    }
    if !duration.is_empty() {
        u.push_str(&format!("duration={}&", net::percent_encode(duration)));
    }
    // Laravel reads repeated `tags[]=`, so the name is repeated per value.
    for t in tags {
        if !t.is_empty() {
            u.push_str(&format!("tags%5B%5D={}&", net::percent_encode(t)));
        }
    }
    if broad && !tags.is_empty() {
        u.push_str("broad=on&");
    }
    u.push_str(&format!("page={page}"));
    u
}

pub fn tag_groups_json() -> String {
    serde_json::to_string(&tags::tag_groups()).unwrap_or_else(|_| "[]".into())
}

pub fn fetch_playlist(id: i64) -> Result<String, String> {
    let html = net::get(&format!("{BASE}/playlist?list={id}&sort=latest"))?;
    let pl = html::parse_playlist(&html, id)?;
    serde_json::to_string(&pl).map_err(|e| e.to_string())
}

/// Translates a title to Simplified Chinese. Exposed to the UI so the collection
/// header can be localized without making [fetch_playlist] wait on a second round
/// trip every time the page opens.
pub fn translate_title(text: &str) -> String {
    translate::translate_zh(text)
}

// -------------------------------------------------------------- random picks

/// Highest id we have seen so far.
///
/// The catalogue is one monotonically increasing integer space (`watch?v=<n>`),
/// so the newest upload also names the top of the range. Every parsed listing
/// nudges this up, which keeps random picks from drifting into the future as
/// the site publishes more videos.
static MAX_SEEN: OnceLock<Mutex<i64>> = OnceLock::new();

/// Floor of the id range. Below this the id either redirects off-site (deleted
/// uploads 302 to javchu.com) or predates the current catalogue.
///
/// The range is *not* contiguous, and it is not even all ours. Measured on
/// 2026-11 by walking the space in 1 s steps (concurrent sampling trips
/// Cloudflare's 429, so this has to be slow) and cross-checking against 542 ids
/// scraped from ten listing pages:
///
/// | ids             | owner / state                                      |
/// |-----------------|----------------------------------------------------|
/// | 1–9             | javchu (302 to the other catalogue)                 |
/// | 10–98           | hanime1                                            |
/// | 99–122          | javchu                                             |
/// | 123–166774      | both, interleaved in runs of a few thousand         |
/// | 166775–401053   | **empty** — 150 random samples, all 403, and javchu
/// |                 | 403s there too; never appears in any listing        |
/// | 401054–408977   | both, interleaved; 408977 is the newest id          |
/// | ≥408978         | 403; above the newest upload                        |
///
/// Within 123–166774: 22.5k–25k H, 40.5k–48k J, 52.5k–55k J, **65k–95k J**,
/// 97.5k–105k H, 117.5k–140k J, then a fine-grained mix of H and J up to
/// 166.6k. The second block behaves the same way (401k–402.6k J, ~402.9k H,
/// 403.1k–404.1k J, a tight H/J braid across 404.4k–404.5k, then
/// 404.53k–408.97k H). `H` = hanime1, `J` = javchu.
///
/// Each site 302s to the other for ids it does not own, in both directions, so
/// this is one shared integer sequence split between two catalogues rather than
/// two independent ranges.
///
/// Ids do **not** sort by time, and the date printed next to the view counter
/// is the *release* date of the work, not when it was uploaded here — a 1990 OVA
/// can sit at id 24885 while a 2026 clip sits at id 300. So per-id dates say
/// nothing about the ordering of the id space; only the `最新上傳` listing does,
/// and that one interleaves ids wildly across pages. The gap is therefore not a
/// jump caused by a collision: both catalogues were simply still far short of
/// 401k when the low block stopped at 166774.
///
/// 403 is the site's answer for "this id has no upload" — impossible ids like
/// 999999999 return it too, and it does not change when the client's egress IP
/// changes, so it is not a rate limit.
///
/// Consequence for sampling: a uniform draw over the whole space lands in the
/// gap, a dead id, or a javchu id ~97% of the time, which is why callers that
/// need a playable video go through a listing page instead.
pub const RANDOM_ID_MIN: i64 = 50;

fn max_seen_cell() -> &'static Mutex<i64> {
    MAX_SEEN.get_or_init(|| Mutex::new(410_000))
}

fn note_id(id: i64) {
    if id <= 0 {
        return;
    }
    let mut g = max_seen_cell().lock().unwrap();
    if id > *g {
        *g = id;
    }
}

/// xorshift64*: good enough for "give me a different video", and it keeps the
/// dependency list at zero (the core has no `rand`).
fn next_rand(state: &mut u64) -> u64 {
    let mut x = *state;
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    *state = x;
    x.wrapping_mul(0x2545_F491_4F6C_DD1D)
}

fn seed() -> u64 {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x9E37_79B9_7F4A_7C15);
    // Mix in the address of a stack local so two launches in the same
    // nanosecond (ASLR aside) still diverge.
    let local = 0u8;
    nanos ^ ((&local as *const u8 as u64) << 17) | 1
}

pub fn random_id() -> i64 {
    let hi = *max_seen_cell().lock().unwrap();
    let mut st = seed();
    let span = (hi - RANDOM_ID_MIN).max(1) as u64;
    RANDOM_ID_MIN + (next_rand(&mut st) % span) as i64
}

/// A uniform draw from the id space.
///
/// Kept as a catalogue-level randomiser for callers that do not need a playable
/// page back. Do not build a feed on it: the space has a ~234k-wide empty hole in
/// the middle (see [RANDOM_ID_MIN]) and even the populated blocks answer 403 for
/// most ids, so a draw almost never lands on a live upload. Anything that needs a
/// *video* should sample a listing page instead (see `random_detail_of_genre`).
pub fn random_detail(tries: u32) -> Result<String, String> {
    random_detail_in("", tries)
}

/// Largest page each genre has reported.
///
/// Per genre, not one global cell: a single shared value meant sampling 裏番
/// after visiting 泡麵番 (which only has 2 pages) rolled pages 1-2 forever, so
/// the result looked barely random at all.
static MAX_PAGE_SEEN: OnceLock<Mutex<HashMap<String, u32>>> = OnceLock::new();

fn max_page_map() -> &'static Mutex<HashMap<String, u32>> {
    MAX_PAGE_SEEN.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Records the page count a listing reported for `genre`.
fn note_max_page(genre: &str, max_page: u32) {
    let key = if genre.trim().is_empty() { "全部" } else { genre.trim() };
    let mut g = max_page_map().lock().unwrap();
    let slot = g.entry(key.to_string()).or_insert(1);
    *slot = (*slot).max(max_page.max(1));
}

fn max_page_for(genre: &str) -> u32 {
    let key = if genre.trim().is_empty() { "全部" } else { genre.trim() };
    max_page_map().lock().unwrap().get(key).copied().unwrap_or(1)
}

/// Random title, optionally restricted to one category.
///
/// Without a category the id space *is* the catalogue, so sampling ids gives a
/// uniform draw — no listing request needed. With a category that stops paying:
/// the id says nothing about the genre, so each wrong pick is a full page fetched
/// and thrown away. Those go through a random listing page instead, where every
/// card is already the right category.
pub fn random_detail_in(genre: &str, tries: u32) -> Result<String, String> {
    // Both cases go through the listing. Sampling bare ids looks tempting — the
    // number in `watch?v=` is a contiguous catalogue — but measured on this site
    // only about one in five ids still answers a playable page (deleted uploads
    // 302 off-site, gated ones 403), so a batch of six ran through ~30 requests and
    // the feed looked frozen. A random listing page always yields playable cards
    // for two requests, and random page + random card is still a uniform draw.
    random_detail_of_genre(genre.trim(), tries)
}

/// Rolls a random page of `genre` and picks one of its cards.
///
/// Two requests worst case: the listing (which also reports the page count) and
/// the watch page of the chosen card. Pages past the end come back with an empty
/// card list, so the pick is retried against the count the response just gave us.
fn random_detail_of_genre(genre: &str, tries: u32) -> Result<String, String> {
    let mut st = seed();
    let mut last = String::from("no cards on the sampled page");
    for _ in 0..tries.max(1) {
        let hi = max_page_for(genre).max(1) as u64;
        let page = 1 + (next_rand(&mut st) % hi) as u32;
        let html = match net::get(&url_for_list(genre, "最新上市", "", "", page)) {
            Ok(v) => v,
            Err(e) => {
                last = e;
                continue;
            }
        };
        let lp = match html::parse_list(&html, page) {
            Ok(v) => v,
            Err(e) => {
                last = e;
                continue;
            }
        };
        note_max_page(genre, lp.max_page);
        if lp.items.is_empty() {
            continue;
        }
        let pick = &lp.items[(next_rand(&mut st) % lp.items.len() as u64) as usize];
        match fetch_detail(pick.id) {
            Ok(json) => return Ok(json),
            Err(e) => last = e,
        }
    }
    Err(last)
}

/// A batch of cards drawn from several random listing pages.
///
/// Random page + random card on it is the only affordable way to sample this
/// catalogue. Measuring id sampling over the three hanime1 blocks (`10–98`,
/// `123–166774`, `401054–408977`) gave a 20% hit rate on 50 draws — 27 landed on
/// the AV catalogue that shares the numbering, 13 on dead ids — so six videos
/// meant ~30 page fetches and the feed looked frozen. A listing page returns ~59
/// playable cards in one request.
///
/// One page is not enough though: a page is one slice of `最新上市`, so six cards
/// off it are six uploads from the same few days and the feed stops feeling
/// random. Distinct pages per batch spread the draw while keeping the cost at
/// roughly one request per two videos.
///
/// Within a page the cards are taken at strided slots rather than from the
/// head: neighbouring cards on a listing are adjacent ids (the list is upload
/// ordered), and pulling them in a row produced visible runs like `#408997`,
/// `#408996`, `#408994` that read as "not random" even though the pages were
/// rolled randomly.
pub fn random_cards(genre: &str, count: u32) -> Result<String, String> {
    let want = genre.trim();
    let count = count.max(1);
    let mut st = seed();
    let mut last = String::from("no cards on the sampled page");
    // Learn the depth before rolling. Out-of-range pages just echo the page number
    // they were asked for, so probing deep teaches us nothing; page 1 always
    // reports the genre's real `max_page`, and without it the first random call of
    // a session was pinned to page 1 and looked barely random.
    if max_page_for(want) <= 1 {
        if let Ok(html) = net::get(&url_for_list(want, "最新上市", "", "", 1)) {
            if let Ok(lp) = html::parse_list(&html, 1) {
                note_max_page(want, lp.max_page);
            }
        }
    }

    // Two cards per page, taken from slots spread across it. Three per page put
    // near-consecutive ids in one batch (#408997 / #408996 / #408994) because a
    // page is an upload-ordered slice, and the feed immediately read as "not
    // random" even though the pages themselves were rolled randomly.
    let per_page = 2u32;
    let pages_needed = count.div_ceil(per_page).max(1);
    let mut items: Vec<Card> = Vec::new();
    let mut seen: std::collections::HashSet<i64> = std::collections::HashSet::new();
    let mut used_pages: std::collections::HashSet<u32> = std::collections::HashSet::new();
    let mut max_page = max_page_for(want);

    // Two attempts per page so a bad request does not shrink the batch, but a
    // hard failure stops early instead of hammering a site that is down.
    for _ in 0..(pages_needed * 2).max(1) {
        if items.len() >= count as usize {
            break;
        }
        let hi = max_page.max(1) as u64;
        let mut page = 1 + (next_rand(&mut st) % hi) as u32;
        // Re-roll once on a page already used this batch; with 373 pages collisions
        // are rare, but a small genre (泡麵番 has 2) would otherwise keep hitting
        // the same slice.
        if used_pages.contains(&page) && hi > 1 {
            page = 1 + (next_rand(&mut st) % hi) as u32;
        }
        let html = match net::get(&url_for_list(want, "最新上市", "", "", page)) {
            Ok(v) => v,
            Err(e) => {
                last = e;
                continue;
            }
        };
        let lp = match html::parse_list(&html, page) {
            Ok(v) => v,
            Err(e) => {
                last = e;
                continue;
            }
        };
        note_max_page(want, lp.max_page);
        max_page = max_page.max(lp.max_page);
        if lp.items.is_empty() {
            continue;
        }
        used_pages.insert(page);
        let len = lp.items.len();
        // Take cards from evenly spread slots across the page instead of from
        // its shuffled prefix. A page is an upload-ordered slice, so adjacent
        // cards are adjacent ids: pulling three in a row put #408997 / #408996 /
        // #408994 in one batch and the feed read as "not random" even though the
        // pages themselves were rolled randomly. Walking the page in strides
        // keeps consecutive numbering out of a batch.
        let want_here = (count as usize - items.len()).min(per_page as usize);
        let stride = (len / want_here.max(1)).max(1);
        let base = (next_rand(&mut st) % len as u64) as usize;
        let mut page_items = lp.items;
        for k in 0..want_here {
            if items.len() >= count as usize {
                break;
            }
            let idx = (base + k * stride) % page_items.len();
            let c = page_items.remove(idx);
            note_id(c.id);
            if seen.insert(c.id) {
                items.push(c);
            }
            if page_items.is_empty() {
                break;
            }
        }
    }

    if items.is_empty() {
        return Err(last);
    }
    // Final shuffle: cards are collected two per page, so without this the feed
    // shows them in page-sized pairs and the same page's two ids stay adjacent.
    for i in (1..items.len()).rev() {
        let j = (next_rand(&mut st) % (i as u64 + 1)) as usize;
        items.swap(i, j);
    }
    let page = ListPage {
        has_next: true,
        max_page,
        page: 0,
        items,
    };
    serde_json::to_string(&page).map_err(|e| e.to_string())
}

pub fn fetch_detail(id: i64) -> Result<String, String> {
    let url = format!("{BASE}/watch?v={id}");
    let html = net::get(&url)?;
    // Delisted uploads do not 404: the site answers 302 to a *different*
    // catalogue (javchu.com) whose pages reuse the same numeric ids but hold
    // unrelated films. Parsing that body would silently return someone else's
    // video under this id, so a page that no longer belongs to this site is
    // reported as missing instead.
    if !html.contains(net::MIRROR_HOST) && !html.contains("hanime1.me") {
        return Err("video is no longer available".into());
    }
    let mut d = html::parse_watch(&html, id)?;
    d.related.retain(|c| c.id != id);
    note_id(id);
    for c in &d.related {
        note_id(c.id);
    }
    serde_json::to_string(&d).map_err(|e| e.to_string())
}

// ----------------------------------------------------------------- updates

pub const RELEASES_API: &str = "https://api.github.com/repos/Czz45/HanimeApp/releases/latest";

/// Latest published release, as a small JSON object for the UI:
/// `{"version":"1.0.1","url":"…apk","notes":"…","published":"…"}`.
///
/// GitHub's API is unauthenticated here (60 requests/hour per IP), which is far
/// more than a manual "check for updates" needs; failures are reported as-is so
/// the settings row can show the real reason instead of a generic error.
pub fn check_update() -> Result<String, String> {
    let body = net::get_json(RELEASES_API)?;
    let v: serde_json::Value =
        serde_json::from_str(&body).map_err(|e| format!("bad release json: {e}"))?;
    let tag = v
        .get("tag_name")
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .trim_start_matches('v')
        .to_string();
    let notes = v
        .get("body")
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .chars()
        .take(600)
        .collect::<String>();
    let published = v
        .get("published_at")
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .to_string();
    // Prefer the APK asset over `html_url`: the settings row opens it directly.
    let url = v
        .get("assets")
        .and_then(|a| a.as_array())
        .and_then(|a| {
            a.iter()
                .find(|x| {
                    x.get("name")
                        .and_then(|n| n.as_str())
                        .is_some_and(|n| n.ends_with(".apk"))
                })
                .and_then(|x| x.get("browser_download_url"))
                .and_then(|u| u.as_str())
        })
        .or_else(|| v.get("html_url").and_then(|u| u.as_str()))
        .unwrap_or("")
        .to_string();
    if tag.is_empty() {
        return Err("release has no tag".into());
    }
    let out = serde_json::json!({
        "version": tag,
        "url": url,
        "notes": notes,
        "published": published,
    });
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

/// Progress of the in-app update download, polled by the settings screen.
static UPDATE: OnceLock<Mutex<(u64, u64, String, String)>> = OnceLock::new();

fn update_cell() -> &'static Mutex<(u64, u64, String, String)> {
    // (done, total, state, path-or-error)
    UPDATE.get_or_init(|| Mutex::new((0, 0, "idle".into(), String::new())))
}

/// `{"done":N,"total":M,"state":"running|done|error","detail":"path or message"}`.
pub fn update_download_state() -> String {
    let g = update_cell().lock().unwrap();
    serde_json::json!({
        "done": g.0,
        "total": g.1,
        "state": g.2,
        "detail": g.3,
    })
    .to_string()
}

/// Downloads the update APK into the app cache so it can be handed to the
/// installer.
///
/// Android's package installer cannot open a `https://` URL; it needs a readable
/// file owned by this app (or a FileProvider), which is why the settings screen
/// cannot simply fire an ACTION_VIEW intent at the release link the way it used
/// to. The transfer runs detached and reports through [update_download_state].
pub fn download_update(url: &str, dest: &str) -> Result<String, String> {
    if url.trim().is_empty() {
        return Err("更新地址为空".to_string());
    }
    {
        let mut g = update_cell().lock().unwrap();
        if g.2 == "running" {
            return Err("正在下载中".to_string());
        }
        *g = (0, 0, "running".into(), String::new());
    }
    let url = url.to_string();
    let dest = dest.to_string();
    std::thread::spawn(move || {
        let fail = |msg: String| {
            let mut g = update_cell().lock().unwrap();
            *g = (0, 0, "error".into(), msg);
        };
        if let Some(parent) = std::path::Path::new(&dest).parent() {
            if let Err(e) = std::fs::create_dir_all(parent) {
                fail(e.to_string());
                return;
            }
        }
        let total = match net::file_size(&url) {
            Ok((t, _)) => t,
            Err(e) => {
                fail(e);
                return;
            }
        };
        {
            let mut g = update_cell().lock().unwrap();
            g.1 = total;
        }
        let resp = match net::get_stream(&url, None) {
            Ok(r) => r,
            Err(e) => {
                fail(e);
                return;
            }
        };
        let mut reader = resp.into_reader();
        let mut file = match std::fs::File::create(&dest) {
            Ok(f) => f,
            Err(e) => {
                fail(e.to_string());
                return;
            }
        };
        let mut buf = vec![0u8; 256 * 1024];
        let mut done = 0u64;
        loop {
            let n = match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => n,
                Err(e) => {
                    fail(e.to_string());
                    return;
                }
            };
            if let Err(e) = file.write_all(&buf[..n]) {
                fail(e.to_string());
                return;
            }
            done += n as u64;
            let mut g = update_cell().lock().unwrap();
            g.0 = done;
        }
        drop(file);
        let mut g = update_cell().lock().unwrap();
        *g = (done, total.max(done), "done".into(), dest);
    });
    Ok("{\"started\":true}".to_string())
}

// -------------------------------------------------------------- downloader

static CANCEL: OnceLock<Mutex<HashMap<i64, std::sync::Arc<AtomicBool>>>> = OnceLock::new();

fn cancels() -> &'static Mutex<HashMap<i64, std::sync::Arc<AtomicBool>>> {
    CANCEL.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn download_dir() -> PathBuf {
    let s = settings_cache().lock().unwrap();
    if !s.storage_dir.trim().is_empty() {
        PathBuf::from(s.storage_dir.trim())
    } else {
        state_dir().join("downloads")
    }
}

/// Strips characters that are legal on Android but hostile to a file name.
/// Playlist titles come from uploaders and routinely contain `/`, `:` or `?`.
fn safe_folder(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '\n' | '\r' | '\t' => '_',
            c => c,
        })
        .collect();
    let trimmed = cleaned.trim().trim_matches('.').trim();
    // Long CJK titles are common; 60 chars keeps the whole path well under
    // the 255-byte limit once the file name is appended.
    let mut out: String = trimmed.chars().take(60).collect();
    if out.is_empty() {
        out = "未分类".into();
    }
    out
}

/// Directory a download belongs in: a folder per playlist when the title came
/// from one, else the download root.
pub fn dir_for_task(list_id: i64, list_title: &str) -> PathBuf {
    let root = download_dir();
    if list_id > 0 && !list_title.trim().is_empty() {
        return root.join(safe_folder(list_title));
    }
    root
}

pub fn file_for_in(dir: &Path, id: i64, quality: u32, title: &str) -> String {
    let prefix = safe_folder(title);
    let name = if title.trim().is_empty() {
        format!("{id}-{quality}p.mp4")
    } else {
        format!("{id}-{prefix}-{quality}p.mp4")
    };
    dir.join(name).to_string_lossy().to_string()
}

pub fn file_for(id: i64, quality: u32) -> String {
    file_for_in(&download_dir(), id, quality, "")
}

fn find_source(d: &Detail, quality: u32) -> Option<Source> {
    d.sources
        .iter()
        .min_by_key(|s| (s.quality as i64 - quality as i64).abs())
        .cloned()
}

/// Starts a download and returns the new task as JSON. The transfer runs on a
/// detached thread and reports progress through `library.json`.
///
/// `list_id`/`list_title` come from the playlist the user opened the title in;
/// when present the file lands in `<downloads>/<playlist>/` so a series stays
/// together on disk without the user filing anything by hand.
pub fn start_download_in(
    id: i64,
    quality: u32,
    list_id: i64,
    list_title: &str,
) -> Result<String, String> {
    let detail_json = fetch_detail(id)?;
    let d: Detail = serde_json::from_str(&detail_json).map_err(|e| e.to_string())?;
    let src = find_source(&d, quality).ok_or_else(|| "no source".to_string())?;
    // A title opened outside a playlist can still belong to one, so fall back to
    // whatever the watch page itself reported.
    let (lid, ltitle) = if list_id > 0 {
        (list_id, list_title.to_string())
    } else {
        (d.list_id, d.list_title.clone())
    };
    // Localize the folder name on the single-download path too; the collection
    // page passes its title in already translated, but a title reached from the
    // home feed arrives with the raw Japanese playlist name.
    let ltitle = if ltitle.trim().is_empty() {
        ltitle
    } else {
        translate::translate_zh(&ltitle)
    };
    let dir = dir_for_task(lid, &ltitle);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = file_for_in(&dir, id, src.quality, &d.title);

    let task = DownloadTask {
        id,
        title: d.title.clone(),
        cover: d.cover.clone(),
        quality: src.quality,
        path: path.clone(),
        total: 0,
        done: 0,
        state: "queued".into(),
        error: String::new(),
        list_id: lid,
        list_title: ltitle,
    };
    {
        let mut lib = library().lock().unwrap();
        lib.retain(|t| t.id != id);
        lib.push(task.clone());
    }
    save_library();

    let cancel = std::sync::Arc::new(AtomicBool::new(false));
    cancels().lock().unwrap().insert(id, cancel.clone());
    let url = src.url.clone();
    std::thread::spawn(move || {
        run_download(id, &url, &path, cancel);
    });

    Ok(serde_json::to_string(&task).unwrap_or_default())
}

pub fn start_download(id: i64, quality: u32) -> Result<String, String> {
    start_download_in(id, quality, 0, "")
}

/// Queues every title of a playlist.
///
/// The site has no bulk endpoint, so each title needs its own watch-page fetch
/// to learn its file size and quality list. Those run one after another on a
/// single thread: six parallel transfers would just fight over the connection
/// and finish at the same time anyway, while a serial queue also keeps the
/// files in playlist order on disk.
pub fn start_playlist_download(list_id: i64, quality: u32) -> Result<String, String> {
    let pl_json = fetch_playlist(list_id)?;
    let pl: Playlist = serde_json::from_str(&pl_json).map_err(|e| e.to_string())?;
    if pl.items.is_empty() {
        return Err("合集为空".to_string());
    }
    let ids: Vec<i64> = pl.items.iter().map(|c| c.id).collect();
    // Folder name is the localized title, so the download tree is readable in the
    // same language the rest of the UI uses. Falls back to the original when the
    // translation endpoint is unreachable.
    let list_title = translate::translate_zh(&pl.title);

    // Register every task up front so the downloads page shows the whole series
    // as pending instead of growing one row at a time.
    {
        let mut lib = library().lock().unwrap();
        for card in &pl.items {
            lib.retain(|t| t.id != card.id);
            lib.push(DownloadTask {
                id: card.id,
                title: card.title.clone(),
                cover: card.cover.clone(),
                quality: 0,
                path: String::new(),
                total: 0,
                done: 0,
                state: "queued".into(),
                error: String::new(),
                list_id,
                list_title: list_title.clone(),
            });
        }
    }
    save_library();

    let dir = dir_for_task(list_id, &list_title);
    std::thread::spawn(move || {
        for id in ids {
            let cancel = std::sync::Arc::new(AtomicBool::new(false));
            cancels().lock().unwrap().insert(id, cancel.clone());
            if cancel.load(Ordering::Relaxed) {
                break;
            }
            let detail = match fetch_detail(id).and_then(|j| {
                serde_json::from_str::<Detail>(&j).map_err(|e| e.to_string())
            }) {
                Ok(d) => d,
                Err(e) => {
                    update_task(id, |t| {
                        t.state = "error".into();
                        t.error = e;
                    });
                    continue;
                }
            };
            let src = match find_source(&detail, quality) {
                Some(s) => s,
                None => {
                    update_task(id, |t| {
                        t.state = "error".into();
                        t.error = "no source".into();
                    });
                    continue;
                }
            };
            if let Err(e) = std::fs::create_dir_all(&dir) {
                update_task(id, |t| {
                    t.state = "error".into();
                    t.error = e.to_string();
                });
                continue;
            }
            let path = file_for_in(&dir, id, src.quality, &detail.title);
            update_task(id, |t| {
                t.quality = src.quality;
                t.path = path.clone();
                t.total = 0;
                t.done = 0;
                t.state = "queued".into();
                t.title = detail.title.clone();
                t.cover = detail.cover.clone();
            });
            run_download(id, &src.url, &path, cancel);
        }
    });

    Ok(format!("{{\"queued\":{}}}", pl.items.len()))
}

fn update_task(id: i64, f: impl FnOnce(&mut DownloadTask)) {
    {
        let mut lib = library().lock().unwrap();
        if let Some(t) = lib.iter_mut().find(|t| t.id == id) {
            f(t);
        }
    }
    save_library();
}

fn run_download(id: i64, url: &str, path: &str, cancel: std::sync::Arc<AtomicBool>) {
    update_task(id, |t| t.state = "running".into());

    let existing = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);

    // Resume from whatever is already on disk instead of restarting.
    let (total, _) = match net::file_size(url) {
        Ok(v) => v,
        Err(e) => {
            update_task(id, |t| {
                t.state = "error".into();
                t.error = e;
            });
            return;
        }
    };
    update_task(id, |t| t.total = total);

    let mut file = match std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
    {
        Ok(f) => f,
        Err(e) => {
            update_task(id, |t| {
                t.state = "error".into();
                t.error = e.to_string();
            });
            return;
        }
    };

    if existing >= total && total > 0 {
        update_task(id, |t| {
            t.done = total;
            t.state = "done".into();
        });
        return;
    }

    let range = if existing > 0 { Some((existing, None)) } else { None };
    let resp = match net::get_stream(url, range) {
        Ok(r) => r,
        Err(e) => {
            update_task(id, |t| {
                t.state = "error".into();
                t.error = e;
            });
            return;
        }
    };

    let mut reader = resp.into_reader();
    let mut buf = vec![0u8; 256 * 1024];
    let mut done = existing;
    let mut last_save = std::time::Instant::now();
    loop {
        if cancel.load(Ordering::Relaxed) {
            update_task(id, |t| {
                t.done = done;
                t.state = "paused".into();
            });
            return;
        }
        match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                use std::io::Write;
                if let Err(e) = file.write_all(&buf[..n]) {
                    update_task(id, |t| {
                        t.state = "error".into();
                        t.error = e.to_string();
                    });
                    return;
                }
                done += n as u64;
                if last_save.elapsed().as_millis() > 400 {
                    update_task(id, |t| t.done = done);
                    last_save = std::time::Instant::now();
                }
            }
            Err(e) => {
                update_task(id, |t| {
                    t.done = done;
                    t.state = "error".into();
                    t.error = e.to_string();
                });
                return;
            }
        }
    }
    update_task(id, |t| {
        t.done = done;
        t.state = "done".into();
        t.error.clear();
    });
}

pub fn cancel_download(id: i64) {
    if let Some(c) = cancels().lock().unwrap().get(&id) {
        c.store(true, Ordering::Relaxed);
    }
    update_task(id, |t| {
        if t.state == "queued" || t.state == "running" {
            t.state = "paused".into();
        }
    });
}

pub fn remove_download(id: i64, delete_file: bool) {
    let path = {
        let mut lib = library().lock().unwrap();
        let p = lib.iter().find(|t| t.id == id).map(|t| t.path.clone());
        lib.retain(|t| t.id != id);
        p
    };
    save_library();
    if delete_file {
        if let Some(p) = path {
            let _ = std::fs::remove_file(p);
        }
    }
}

pub fn local_file_exists(id: i64) -> bool {
    let lib = library().lock().unwrap();
    lib.iter()
        .find(|t| t.id == id)
        .map(|t| Path::new(&t.path).exists())
        .unwrap_or(false)
}

/// Local files are re-scanned on start so a file deleted outside the app does
/// not linger as playable.
pub fn rescan_library() {
    let mut changed = false;
    {
        let mut lib = library().lock().unwrap();
        for t in lib.iter_mut() {
            let exists = Path::new(&t.path).exists();
            if exists && t.state != "done" {
                if let Ok(m) = std::fs::metadata(&t.path) {
                    if m.len() > 0 && (t.total == 0 || m.len() >= t.total) {
                        t.state = "done".into();
                        t.done = m.len();
                        t.total = m.len();
                        changed = true;
                    }
                }
            } else if !exists && t.state == "done" {
                t.state = "missing".into();
                changed = true;
            }
        }
    }
    if changed {
        save_library();
    }
}
