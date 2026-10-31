// Desktop harness: verifies the parsers against saved HTML samples and, with
// `net`, against the live site. Used to debug the Rust core without rebuilding
// the APK every time.

use std::path::PathBuf;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mode = args.first().map(|s| s.as_str()).unwrap_or("samples");

    match mode {
        "samples" => {
            let dir = args
                .get(1)
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("/tmp"));
            check_file(&dir.join("home.html"), "home");
            check_file(&dir.join("watch.html"), "watch");
            check_file(&dir.join("search.html"), "list");
        }
        "net" => {
            let what = args.get(1).map(|s| s.as_str()).unwrap_or("home");
            let r = match what {
                "home" => hanime_core::fetch_home(),
                "list" => hanime_core::fetch_list("裏番", "最新上市", "", "", 1),
                "search" => hanime_core::fetch_search("poko", 1),
                "detail" => hanime_core::fetch_detail(408935),
                _ => Err("unknown".into()),
            };
            match r {
                Ok(s) => {
                    let mut cut = s.len().min(4000);
                    while cut > 0 && !s.is_char_boundary(cut) {
                        cut -= 1;
                    }
                    println!("{}", &s[..cut]);
                }
                Err(e) => println!("ERR {e}"),
            }
        }
        "raw" => {
            let url = args.get(1).cloned().unwrap_or_default();
            let out = args.get(2).cloned().unwrap_or_else(|| "../.tmp/raw.html".into());
            match hanime_core::net::get(&url) {
                Ok(s) => {
                    std::fs::write(&out, &s).unwrap();
                    println!("OK {} bytes -> {out}", s.len());
                }
                Err(e) => println!("ERR {e}"),
            }
        }
        "lf" => {
            // Parse an arbitrary saved list page, e.g. a genre other than 裏番
            // (those use a different card template on the live site).
            let path = args.get(1).cloned().unwrap_or_default();
            let html = std::fs::read_to_string(&path).unwrap_or_default();
            match hanime_core::html::parse_list(&html, 1) {
                Ok(lp) => {
                    println!(
                        "items {} page {}/{} has_next {}",
                        lp.items.len(),
                        lp.page,
                        lp.max_page,
                        lp.has_next
                    );
                    for c in lp.items.iter().take(5) {
                        println!("  {} wide={} {} | {}", c.id, c.wide, c.title, c.dur);
                    }
                }
                Err(e) => println!("ERR {e}"),
            }
        }
        "wt" => {
            // Parse an arbitrary saved watch page and dump the localized title.
            let path = args.get(1).cloned().unwrap_or_default();
            let id: i64 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(0);
            let html = std::fs::read_to_string(&path).unwrap_or_default();
            match hanime_core::html::parse_watch(&html, id) {
                Ok(d) => println!(
                    "title {:?}\ncn    {:?}\nviews {} date {} genre {} sources {:?}",
                    d.title,
                    d.cn_title,
                    d.views,
                    d.date,
                    d.genre,
                    d.sources.iter().map(|s| s.quality).collect::<Vec<_>>()
                ),
                Err(e) => println!("ERR {e}"),
            }
        }
        other => println!("unknown mode {other}"),
    }
}

fn check_file(path: &PathBuf, kind: &str) {
    let html = match std::fs::read_to_string(path) {
        Ok(h) => h,
        Err(e) => {
            println!("[{kind}] cannot read {}: {e}", path.display());
            return;
        }
    };
    println!("[{kind}] loaded {} bytes", html.len());
    match kind {
        "home" => match hanime_core::html::parse_home(&html) {
            Ok(secs) => {
                println!("[{kind}] {} sections", secs.len());
                for s in &secs {
                    println!(
                        "  - {} ({} items) first: {:?}",
                        s.title,
                        s.items.len(),
                        s.items
                            .first()
                            .map(|c| (&c.title, &c.dur, &c.score, &c.views, &c.time))
                    );
                }
            }
            Err(e) => println!("[{kind}] ERR {e}"),
        },
        "list" => match hanime_core::html::parse_list(&html, 1) {
            Ok(lp) => {
                println!(
                    "[{kind}] {} items, page {}/{}, has_next {}",
                    lp.items.len(),
                    lp.page,
                    lp.max_page,
                    lp.has_next
                );
                for c in lp.items.iter().take(3) {
                    println!(
                        "  - {} {} {}",
                        c.id,
                        c.title,
                        &c.cover[..c.cover.len().min(60)]
                    );
                }
            }
            Err(e) => println!("[{kind}] ERR {e}"),
        },
        "watch" => match hanime_core::html::parse_watch(&html, 408935) {
            Ok(d) => {
                println!(
                    "[{kind}] {} | cn {:?} | dur {} | views {} | date {} | author {} | genre {} | {} ({}) | tags {} | sources {:?} | related {}",
                    d.title, d.cn_title, d.duration, d.views, d.date, d.author, d.genre, d.like_pct, d.like_count,
                    d.tags.len(),
                    d.sources.iter().map(|s| s.quality).collect::<Vec<_>>(),
                    d.related.len()
                );
                println!("  cover {}", &d.cover[..d.cover.len().min(80)]);
                println!(
                    "  related0 {:?}",
                    d.related.first().map(|c| (&c.title, &c.dur, &c.score))
                );
                println!("  desc {}", &d.desc[..d.desc.len().min(200)]);
            }
            Err(e) => println!("[{kind}] ERR {e}"),
        },
        _ => {}
    }
}
