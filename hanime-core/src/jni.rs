//! JNI surface. Kotlin calls these as `com.hanime.app.core.Native` statics and
//! gets JSON back, so the bridge never has to know about model classes.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

fn from_java(env: &mut JNIEnv, s: JString) -> String {
    env.get_string(&s)
        .map(|j| j.into())
        .unwrap_or_default()
}

fn to_java(env: &JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_init(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) {
    let dir = from_java(&mut env, dir);
    std::env::set_var("HANIME_STATE_DIR", &dir);
    let _ = std::fs::create_dir_all(&dir);
    crate::rescan_library();
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_fetchHome(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = match crate::fetch_home() {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_fetchList(
    mut env: JNIEnv,
    _class: JClass,
    genre: JString,
    sort: JString,
    date: JString,
    duration: JString,
    page: jni::sys::jint,
) -> jstring {
    let g = from_java(&mut env, genre);
    let s = from_java(&mut env, sort);
    let d = from_java(&mut env, date);
    let du = from_java(&mut env, duration);
    let out = match crate::fetch_list(&g, &s, &d, &du, page.max(1) as u32) {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_fetchSearch(
    mut env: JNIEnv,
    _class: JClass,
    query: JString,
    page: jni::sys::jint,
) -> jstring {
    let q = from_java(&mut env, query);
    let out = match crate::fetch_search(&q, page.max(1) as u32) {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_fetchDetail(
    _env: JNIEnv,
    _class: JClass,
    id: jni::sys::jlong,
) -> jstring {
    let out = match crate::fetch_detail(id) {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&_env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_fetchSearchFiltered(
    mut env: JNIEnv,
    _class: JClass,
    query: JString,
    genre: JString,
    sort: JString,
    date: JString,
    duration: JString,
    tags_csv: JString,
    broad: jni::sys::jboolean,
    page: jni::sys::jint,
) -> jstring {
    let q = from_java(&mut env, query);
    let g = from_java(&mut env, genre);
    let s = from_java(&mut env, sort);
    let d = from_java(&mut env, date);
    let du = from_java(&mut env, duration);
    let raw = from_java(&mut env, tags_csv);
    let tags: Vec<String> = raw
        .split('\u{1}')
        .filter(|t| !t.is_empty())
        .map(|t| t.to_string())
        .collect();
    let out = match crate::fetch_search_filtered(
        &q,
        &g,
        &s,
        &d,
        &du,
        &tags,
        broad != 0,
        page.max(1) as u32,
    ) {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_tagGroups(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_java(&env, crate::tag_groups_json())
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_fetchPlaylist(
    env: JNIEnv,
    _class: JClass,
    id: jni::sys::jlong,
) -> jstring {
    let out = match crate::fetch_playlist(id) {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_startPlaylistDownload(
    _env: JNIEnv,
    _class: JClass,
    listId: jni::sys::jlong,
    quality: jni::sys::jint,
) -> jstring {
    let out = match crate::start_playlist_download(listId, quality.max(0) as u32) {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&_env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_checkUpdate(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = match crate::check_update() {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_downloadUpdate(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    dest: JString,
) -> jstring {
    let u = from_java(&mut env, url);
    let d = from_java(&mut env, dest);
    let out = match crate::download_update(&u, &d) {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_updateDownloadState(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_java(&env, crate::update_download_state())
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_translateTitle(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    let t = from_java(&mut env, text);
    to_java(&env, crate::translate_title(&t))
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_startDownload(
    mut env: JNIEnv,
    _class: JClass,
    id: jni::sys::jlong,
    quality: jni::sys::jint,
    listId: jni::sys::jlong,
    listTitle: JString,
) -> jstring {
    let lt = from_java(&mut env, listTitle);
    let out = match crate::start_download_in(id, quality.max(0) as u32, listId, &lt) {
        Ok(v) => v,
        Err(e) => format!("{{\"error\":\"{}\"}}", escape(&e)),
    };
    to_java(&env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_cancelDownload(
    _env: JNIEnv,
    _class: JClass,
    id: jni::sys::jlong,
) {
    crate::cancel_download(id);
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_removeDownload(
    _env: JNIEnv,
    _class: JClass,
    id: jni::sys::jlong,
    delete_file: jni::sys::jboolean,
) {
    crate::remove_download(id, delete_file != 0);
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_rescanLibrary(env: JNIEnv, _class: JClass) {
    crate::rescan_library();
    let _ = env;
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_libraryJson(env: JNIEnv, _class: JClass) -> jstring {
    to_java(&env, crate::library_json())
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_downloadDir(env: JNIEnv, _class: JClass) -> jstring {
    to_java(&env, crate::download_dir().to_string_lossy().to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_localFileExists(
    _env: JNIEnv,
    _class: JClass,
    id: jni::sys::jlong,
) -> jni::sys::jboolean {
    if crate::local_file_exists(id) {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_markWatched(
    _env: JNIEnv,
    _class: JClass,
    id: jni::sys::jlong,
) {
    crate::mark_watched(id);
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_isWatched(
    _env: JNIEnv,
    _class: JClass,
    id: jni::sys::jlong,
) -> jni::sys::jboolean {
    if crate::is_watched(id) {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_watchedJson(env: JNIEnv, _class: JClass) -> jstring {
    to_java(&env, crate::watched_json())
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_clearWatched(env: JNIEnv, _class: JClass) {
    crate::clear_watched();
    let _ = env;
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_getSettingsJson(env: JNIEnv, _class: JClass) -> jstring {
    to_java(&env, crate::get_settings_json())
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_setSettingsJson(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
) -> jstring {
    let j = from_java(&mut env, json);
    to_java(&env, crate::set_settings_json(&j))
}

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"").replace('\n', " ")
}

#[no_mangle]
pub extern "system" fn Java_com_hanime_app_core_Native_setProxy(
    mut env: JNIEnv,
    _class: JClass,
    value: JString,
) {
    let v = from_java(&mut env, value);
    crate::set_system_proxy(&v);
}
