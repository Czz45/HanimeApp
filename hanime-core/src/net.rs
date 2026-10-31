//! HTTP layer. The site sits behind Cloudflare and distinguishes bots by the
//! *full* set of browser headers, not just the User-Agent (verified empirically:
//! a UA alone yields 403 while the complete set yields 200). Keep them together.

use std::sync::Mutex;
use ureq::{Agent, AgentBuilder, Proxy};

pub const UA: &str = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36";

static AGENT: Mutex<Option<(Option<String>, Agent)>> = Mutex::new(None);

fn build_agent(proxy: Option<&str>) -> Agent {
    let mut b = AgentBuilder::new()
        .timeout_connect(std::time::Duration::from_secs(15))
        .timeout_read(std::time::Duration::from_secs(30))
        .timeout_write(std::time::Duration::from_secs(30))
        .user_agent(UA);
    if let Some(p) = proxy {
        if !p.trim().is_empty() {
            // ureq wants a scheme; a bare host:port is the shape Android hands us.
            let raw = p.trim();
            let url = if raw.contains("://") { raw.to_string() } else { format!("http://{raw}") };
            if let Ok(pr) = Proxy::new(&url) {
                b = b.proxy(pr);
            }
        }
    }
    // The bundled webpki roots are what make TLS work without touching the
    // Android system trust store (which Rust cannot read via openssl).
    b.build()
}

/// Drops the cached agent so the next call rebuilds it against a new proxy.
pub fn invalidate_agent() {
    *AGENT.lock().unwrap() = None;
}

fn with_agent<R>(f: impl FnOnce(&Agent) -> R) -> R {
    let mut guard = AGENT.lock().unwrap();
    let want = super::proxy_value().or_else(super::system_proxy_value);
    let need_rebuild = match guard.as_ref() {
        Some((p, _)) => p != &want,
        None => true,
    };
    if need_rebuild {
        *guard = Some((want.clone(), build_agent(want.as_deref())));
    }
    f(&guard.as_ref().unwrap().1)
}

fn apply_headers(req: ureq::Request) -> ureq::Request {
    req.set("User-Agent", UA)
        .set(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        )
        // Traditional Chinese: the site flips its whole UI to simplified text
        // for zh-CN and our selectors are written against one language.
        .set("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
        // Only gzip: ureq cannot decode `br`, and a raw br body is unparseable.
        .set("Accept-Encoding", "gzip")
        .set("Upgrade-Insecure-Requests", "1")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
}

pub fn get(url: &str) -> Result<String, String> {
    let mut last = String::new();
    for attempt in 0..3 {
        let r = with_agent(|agent| {
            let resp = apply_headers(agent.get(url)).call();
            match resp {
                Ok(r) => r.into_string().map_err(|e| e.to_string()),
                Err(e) => Err(format!("{e}")),
            }
        });
        match r {
            Ok(s) => {
                if s.len() > 512 {
                    return Ok(s);
                }
                last = format!("short body ({} bytes)", s.len());
            }
            Err(e) => last = e,
        }
        if attempt < 2 {
            std::thread::sleep(std::time::Duration::from_millis(400 * (attempt + 1) as u64));
        }
    }
    Err(last)
}

/// Streaming GET used by the downloader: returns the raw response so the caller
/// can copy the body byte by byte.
pub fn get_stream(
    url: &str,
    range: Option<(u64, Option<u64>)>,
) -> Result<ureq::Response, String> {
    with_agent(|agent| {
        let mut req = apply_headers(agent.get(url));
        if let Some((start, end)) = range {
            let v = match end {
                Some(e) => format!("bytes={start}-{e}"),
                None => format!("bytes={start}-"),
            };
            req = req.set("Range", &v);
        }
        req.call().map_err(|e| format!("{e}"))
    })
}

pub fn file_size(url: &str) -> Result<(u64, bool), String> {
    let resp = get_stream(url, Some((0, Some(0))))?;
    let total = resp
        .header("content-range")
        .and_then(|v| v.rsplit('/').next().map(|s| s.trim().to_string()))
        .and_then(|s| s.parse::<u64>().ok())
        .or_else(|| {
            resp.header("content-length")
                .and_then(|v| v.parse::<u64>().ok())
        })
        .ok_or_else(|| "unknown size".to_string())?;
    Ok((total, true))
}

pub fn percent_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}
