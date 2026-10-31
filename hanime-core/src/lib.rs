//! Public surface of the Rust core. Everything the Kotlin UI needs is exposed
//! here as plain functions returning JSON strings, so the JNI layer stays a thin
//! string-in/string-out shim and the same code is testable from a desktop CLI.

pub mod html;
pub mod model;
pub mod net;
pub mod tags;

#[cfg(feature = "jni-bridge")]
pub mod jni;

use model::*;
use std::collections::HashMap;
use std::io::Read;
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
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            proxy: String::new(),
            hide_watched: true,
            preferred_quality: 1080,
            storage_dir: String::new(),
            auto_play: false,
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

pub fn fetch_detail(id: i64) -> Result<String, String> {
    let url = format!("{BASE}/watch?v={id}");
    let html = net::get(&url)?;
    let mut d = html::parse_watch(&html, id)?;
    d.related.retain(|c| c.id != id);
    serde_json::to_string(&d).map_err(|e| e.to_string())
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

pub fn file_for(id: i64, quality: u32) -> String {
    download_dir()
        .join(format!("{id}-{quality}p.mp4"))
        .to_string_lossy()
        .to_string()
}

fn find_source(d: &Detail, quality: u32) -> Option<Source> {
    d.sources
        .iter()
        .min_by_key(|s| (s.quality as i64 - quality as i64).abs())
        .cloned()
}

/// Starts a download and returns the new task as JSON. The transfer runs on a
/// detached thread and reports progress through `library.json`.
pub fn start_download(id: i64, quality: u32) -> Result<String, String> {
    let detail_json = fetch_detail(id)?;
    let d: Detail = serde_json::from_str(&detail_json).map_err(|e| e.to_string())?;
    let src = find_source(&d, quality).ok_or_else(|| "no source".to_string())?;
    let dir = download_dir();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = file_for(id, src.quality);

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
