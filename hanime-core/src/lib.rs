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
use std::collections::HashMap;
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

static WATCHED: OnceLock<Mutex<Vec<i64>>> = OnceLock::new();

fn watched() -> &'static Mutex<Vec<i64>> {
    WATCHED.get_or_init(|| {
        let v: Vec<i64> = std::fs::read_to_string(watched_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        Mutex::new(v)
    })
}

pub fn mark_watched(id: i64) {
    let mut g = watched().lock().unwrap();
    if !g.contains(&id) {
        g.push(id);
        if g.len() > 5000 {
            let drop = g.len() - 5000;
            g.drain(0..drop);
        }
        let _ = std::fs::create_dir_all(state_dir());
        let _ = std::fs::write(
            watched_path(),
            serde_json::to_string(&*g).unwrap_or_default(),
        );
    }
}

pub fn is_watched(id: i64) -> bool {
    watched().lock().unwrap().contains(&id)
}

pub fn watched_json() -> String {
    serde_json::to_string(&*watched().lock().unwrap()).unwrap_or_else(|_| "[]".into())
}

pub fn set_watched_json(json: &str) {
    if let Ok(v) = serde_json::from_str::<Vec<i64>>(json) {
        *watched().lock().unwrap() = v;
        let _ = std::fs::create_dir_all(state_dir());
        let _ = std::fs::write(watched_path(), json);
    }
}

pub fn clear_watched() {
    watched().lock().unwrap().clear();
    let _ = std::fs::write(watched_path(), "[]");
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
        let w = watched().lock().unwrap().clone();
        page.items.retain(|c| !w.contains(&c.id));
    }
    page
}

pub fn fetch_home() -> Result<String, String> {
    let html = net::get(&format!("{BASE}/"))?;
    let mut sections = html::parse_home(&html)?;
    let hide = settings_cache().lock().unwrap().hide_watched;
    if hide {
        let w = watched().lock().unwrap().clone();
        for s in &mut sections {
            s.items.retain(|c| !w.contains(&c.id));
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

pub fn fetch_detail(id: i64) -> Result<String, String> {
    let url = format!("{BASE}/watch?v={id}");
    let html = net::get(&url)?;
    let mut d = html::parse_watch(&html, id)?;
    d.related.retain(|c| c.id != id);
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
