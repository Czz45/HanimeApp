//! HTML parsing for the three page shapes we care about: the home page
//! (horizontal rows of rich cards), the search/list page (grid of cover+title
//! cards) and the watch page (player sources + metadata).
//!
//! Selectors are intentionally conservative: the site markup is stable but not
//! semantic, and `tl`'s selector engine only supports a small subset, so we
//! walk classes/ids and check attributes by hand.

use crate::model::*;
use tl::{NodeHandle, Parser, VDom};

fn attr(parser: &Parser, h: NodeHandle, key: &str) -> Option<String> {
    let node = h.get(parser)?;
    let tag = node.as_tag()?;
    tag.attributes()
        .get(key)
        .flatten()
        .map(|b| b.as_utf8_str().to_string())
}

fn has_class(parser: &Parser, h: NodeHandle, cls: &str) -> bool {
    let node = match h.get(parser) {
        Some(n) => n,
        None => return false,
    };
    match node.as_tag() {
        Some(t) => t.attributes().is_class_member(cls),
        None => false,
    }
}

/// `tl` hands back raw inner text, entities included, and the site leans on
/// `&nbsp;` as a separator inside metadata lines, so decode before trimming.
fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.char_indices();
    while let Some((i, c)) = chars.next() {
        if c != '&' {
            out.push(c);
            continue;
        }
        let rest = &s[i..];
        let end = match rest.find(';') {
            Some(e) if e <= 10 => e,
            _ => {
                out.push(c);
                continue;
            }
        };
        let ent = &rest[1..end];
        let rep = match ent {
            "nbsp" => Some(" "),
            "amp" => Some("&"),
            "lt" => Some("<"),
            "gt" => Some(">"),
            "quot" => Some("\""),
            "apos" => Some("'"),
            _ => ent
                .strip_prefix("#x")
                .or_else(|| ent.strip_prefix("#X"))
                .and_then(|h| u32::from_str_radix(h, 16).ok())
                .or_else(|| ent.strip_prefix('#').and_then(|d| d.parse::<u32>().ok()))
                .and_then(char::from_u32)
                .map(|_| ""),
        };
        match rep {
            Some(r) => {
                if r.is_empty() {
                    if let Some(ch) = ent
                        .strip_prefix("#x")
                        .or_else(|| ent.strip_prefix("#X"))
                        .and_then(|h| u32::from_str_radix(h, 16).ok())
                        .or_else(|| ent.strip_prefix('#').and_then(|d| d.parse::<u32>().ok()))
                        .and_then(char::from_u32)
                    {
                        out.push(ch);
                    }
                } else {
                    out.push_str(r);
                }
                // The `&` was consumed by the iterator already; skip the rest
                // of the entity name plus the semicolon.
                for _ in 0..end {
                    chars.next();
                }
            }
            None => out.push(c),
        }
    }
    out
}

fn text(parser: &Parser, h: NodeHandle) -> String {
    match h.get(parser) {
        Some(n) => unescape(n.inner_text(parser).trim()),
        None => String::new(),
    }
}

fn children(parser: &Parser, h: NodeHandle) -> Vec<NodeHandle> {
    let node = match h.get(parser) {
        Some(n) => n,
        None => return vec![],
    };
    match node.as_tag() {
        Some(t) => t.children().top().to_vec(),
        None => vec![],
    }
}

fn find_all<F: Fn(NodeHandle, &Parser) -> bool>(
    dom: &VDom,
    pred: F,
) -> Vec<NodeHandle> {
    let parser = dom.parser();
    dom.query_selector("*")
        .map(|iter| iter.filter(|h| pred(*h, parser)).collect())
        .unwrap_or_default()
}

fn find_first<F: Fn(NodeHandle, &Parser) -> bool>(
    dom: &VDom,
    pred: F,
) -> Option<NodeHandle> {
    let parser = dom.parser();
    dom.query_selector("*").and_then(|mut it| it.find(|h| pred(*h, parser)))
}

pub fn video_id_from_href(href: &str) -> Option<i64> {
    let idx = href.find("/watch?v=")?;
    let rest = &href[idx + 9..];
    let num: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    num.parse().ok()
}

/// Extracts the `secure` CDN url from an <img> src, keeping the token that the
/// CDN requires (a bare `image/cover/<id>.jpg` returns 403).
fn img_src(parser: &Parser, h: NodeHandle) -> String {
    attr(parser, h, "src").unwrap_or_default()
}

fn parse_rich_card(dom: &VDom, container: NodeHandle) -> Option<Card> {
    let parser = dom.parser();
    let mut link: Option<NodeHandle> = None;
    for c in children(parser, container) {
        for gc in children(parser, c) {
            if let Some(href) = attr(parser, gc, "href") {
                if href.contains("/watch?v=") {
                    link = Some(gc);
                    break;
                }
            }
        }
        if link.is_some() {
            break;
        }
    }
    let link = link?;
    let href = attr(parser, link, "href")?;
    let id = video_id_from_href(&href)?;

    let mut card = Card {
        id,
        ..Default::default()
    };
    if let Some(t) = attr(parser, container, "title") {
        card.title = unescape(&t);
    }

    // Descend from the container (not the link) because the upload time and
    // author live in the `.subtitle` sibling *after* the anchor.
    let mut stack: Vec<NodeHandle> = vec![container];
    let mut seen = 0usize;
    while let Some(h) = stack.pop() {
        seen += 1;
        if seen > 60 {
            break;
        }
        if has_class(parser, h, "duration") {
            card.dur = text(parser, h);
        } else if has_class(parser, h, "stats-container") {
            for s in children(parser, h) {
                let t = text(parser, s);
                if t.contains('%') {
                    card.score = t
                        .replace("thumb_up", "")
                        .replace("thumb_up_alt", "")
                        .trim()
                        .to_string();
                } else if !t.is_empty() && card.views.is_empty() {
                    card.views = t;
                }
            }
        } else if has_class(parser, h, "title") && card.title.is_empty() {
            card.title = text(parser, h);
        } else if has_class(parser, h, "main-thumb") {
            card.cover = img_src(parser, h);
        } else if has_class(parser, h, "subtitle-time") {
            card.time = text(parser, h).trim_start_matches('\u{a0}').trim_start_matches('•').trim().to_string();
        }
        for c in children(parser, h) {
            stack.push(c);
        }
    }
    // Author links live in `.subtitle` as `<a href=".../search?query=">`.
    if let Some(sub) = find_first(dom, |h, p| has_class(p, h, "subtitle")) {
        for c in children(parser, sub) {
            if let Some(href) = attr(parser, c, "href") {
                if href.contains("/search?query=") {
                    card.author = text(parser, c);
                    break;
                }
            }
        }
    }
    if card.title.is_empty() {
        card.title = format!("作品 {id}");
    }
    // Home rows / rich list cards always serve the 16:9 still frame.
    card.wide = card.cover.contains("/image/thumbnail/") || card.cover.is_empty();
    Some(card)
}

fn is_rich_card(parser: &Parser, h: NodeHandle) -> bool {
    has_class(parser, h, "video-item-container")
}

pub fn parse_home(html: &str) -> Result<Vec<Section>, String> {
    let dom = tl::parse(html, tl::ParserOptions::default()).map_err(|e| e.to_string())?;
    let parser = dom.parser();

    let row_handles = find_all(&dom, |h, p| has_class(p, h, "home-rows-videos-wrapper"));
    let mut sections = Vec::new();
    for row in row_handles {
        let mut sec = Section::default();
        for c in children(parser, row) {
            if is_rich_card(parser, c) {
                if let Some(card) = parse_rich_card(&dom, c) {
                    sec.items.push(card);
                }
            }
        }
        // The row title lives in the sibling `<a class="horizontal-row-title">`.
        for other in find_all(&dom, |h, p| has_class(p, h, "horizontal-row-title")) {
            let t = text(parser, other);
            if t.is_empty() {
                continue;
            }
            // Match the title to the row by document order proximity: rows and
            // titles alternate, so take the last title that appears before it.
            if let Some((_, bound)) = other.get(parser).and_then(|n| {
                n.as_tag().map(|t| t.boundaries(parser))
            }) {
                if let Some((rstart, _)) = row.get(parser).and_then(|n| {
                    n.as_tag().map(|t| t.boundaries(parser))
                }) {
                    if bound < rstart {
                        sec.title = t
                            .split("查看")
                            .next()
                            .unwrap_or(&t)
                            .trim()
                            .to_string();
                    }
                }
            }
        }
        if !sec.items.is_empty() {
            sections.push(sec);
        }
    }
    if sections.is_empty() {
        return Err("no home sections parsed".into());
    }
    Ok(sections)
}

/// Grid cards as used by `/search`.
///
/// The site ships two completely different templates here: 裏番/泡麵番/新番預告
/// render `.video-card-inner` (cover + title only), while the 3DCG/MMD/Cosplay…
/// families reuse the rich `.video-item-container` markup from the home rows. Both
/// have to be understood or half the genres come back empty.
pub fn parse_list(html: &str, page: u32) -> Result<ListPage, String> {
    let dom = tl::parse(html, tl::ParserOptions::default()).map_err(|e| e.to_string())?;
    let parser = dom.parser();

    let mut items = Vec::new();
    let mut seen = std::collections::HashSet::new();

    // Template A: portrait cover tiles.
    let cards = find_all(&dom, |h, p| has_class(p, h, "video-card-inner"));
    let anchors = find_all(&dom, |h, p| attr(p, h, "href").is_some_and(|v| v.contains("/watch?v=")));

    // Cover+title pairs come from the card divs; fall back to anchors so a
    // markup tweak on one side still yields results.
    for c in cards {
        let mut card = Card::default();
        let mut stack: Vec<NodeHandle> = children(parser, c);
        while let Some(h) = stack.pop() {
            if has_class(parser, h, "home-rows-videos-title") {
                card.title = text(parser, h);
            } else if let Some(src) = attr(parser, h, "src") {
                if src.contains("/image/cover/") || src.contains("/image/thumbnail/") {
                    card.wide = src.contains("/image/thumbnail/");
                    card.cover = src.to_string();
                }
            }
            for gc in children(parser, h) {
                stack.push(gc);
            }
        }
        // The anchor is the card's parent; walk up by searching the document.
        for a in &anchors {
            if let Some(href) = attr(parser, *a, "href") {
                if let Some(id) = video_id_from_href(&href) {
                    let node_bounds = a.get(parser).and_then(|n| n.as_tag().map(|t| t.boundaries(parser)));
                    let card_bounds = c.get(parser).and_then(|n| n.as_tag().map(|t| t.boundaries(parser)));
                    if let (Some((as_, ae)), Some((cs, ce))) = (node_bounds, card_bounds) {
                        if as_ <= cs && ae >= ce {
                            card.id = id;
                            break;
                        }
                    }
                }
            }
        }
        if card.id != 0 && seen.insert(card.id) {
            if card.title.is_empty() {
                card.title = format!("作品 {}", card.id);
            }
            items.push(card);
        }
    }

    // Template B: the same rich cards the home page uses (landscape thumbnails).
    // Ads live in this markup too, but they link to erolabs, so `parse_rich_card`
    // skipping anchors without `/watch?v=` filters them out.
    if items.is_empty() {
        for c in find_all(&dom, |h, p| is_rich_card(p, h)) {
            if let Some(mut card) = parse_rich_card(&dom, c) {
                card.wide = true;
                if seen.insert(card.id) {
                    items.push(card);
                }
            }
        }
    }

    if items.is_empty() {
        for a in anchors {
            if let Some(href) = attr(parser, a, "href") {
                if let Some(id) = video_id_from_href(&href) {
                    if seen.insert(id) {
                        items.push(Card {
                            id,
                            title: format!("作品 {id}"),
                            ..Default::default()
                        });
                    }
                }
            }
        }
    }

    // Pagination: `.page-item` anchors carry `?page=N`.
    let mut max_page = page;
    for a in find_all(&dom, |h, p| attr(p, h, "href").is_some_and(|v| v.contains("page="))) {
        if let Some(href) = attr(parser, a, "href") {
            if let Some(v) = href.split("page=").nth(1) {
                let n: String = v.chars().take_while(|c| c.is_ascii_digit()).collect();
                if let Ok(n) = n.parse::<u32>() {
                    max_page = max_page.max(n);
                }
            }
        }
    }

    if items.is_empty() {
        return Err("no cards parsed".into());
    }
    Ok(ListPage {
        has_next: page < max_page,
        max_page,
        page,
        items,
    })
}

fn meta_content(dom: &VDom, property: &str) -> String {
    let parser = dom.parser();
    find_first(dom, |h, p| {
        let is_meta = h
            .get(p)
            .and_then(|n| n.as_tag().map(|t| t.name().as_utf8_str() == "meta"))
            .unwrap_or(false);
        is_meta
            && (attr(p, h, "property").as_deref() == Some(property)
            || attr(p, h, "name").as_deref() == Some(property))
    })
    .and_then(|h| attr(parser, h, "content"))
    .unwrap_or_default()
}

pub fn parse_watch(html: &str, id: i64) -> Result<Detail, String> {
    let dom = tl::parse(html, tl::ParserOptions::default()).map_err(|e| e.to_string())?;
    let parser = dom.parser();
    let mut d = Detail {
        id,
        ..Default::default()
    };

    d.cover = meta_content(&dom, "og:image");
    d.poster = d.cover.clone();
    d.desc = meta_content(&dom, "description");
    d.duration = meta_content(&dom, "og:video:duration")
        .parse::<u32>()
        .unwrap_or(0);

    if let Some(h) = find_first(&dom, |h, p| attr(p, h, "id").as_deref() == Some("shareBtn-title")) {
        d.title = text(parser, h);
    }
    if d.title.is_empty() {
        d.title = meta_content(&dom, "og:title")
            .trim_end_matches(" - Hanime1.me")
            .to_string();
    }

    // Player sources: `<source src=... size=720>` inside `#player`.
    let sources = find_all(&dom, |h, p| {
        h.get(p)
            .and_then(|n| n.as_tag().map(|t| t.name().as_utf8_str() == "source"))
            .unwrap_or(false)
    });
    for s in sources {
        if let Some(src) = attr(parser, s, "src") {
            let q = attr(parser, s, "size")
                .and_then(|v| v.parse::<u32>().ok())
                .unwrap_or(0);
            if !src.is_empty() {
                d.sources.push(Source {
                    quality: q,
                    url: src.to_string(),
                    size: 0,
                });
            }
        }
    }
    d.sources
        .sort_by(|a, b| b.quality.cmp(&a.quality));

    // "觀看次數：1萬次  2026-10-31" in a plain details div.
    for h in find_all(&dom, |h, p| has_class(p, h, "video-details-wrapper")) {
        let t = text(parser, h);
        // Accept both scripts in case the site serves simplified markup.
        let rest = t
            .strip_prefix("觀看次數：")
            .or_else(|| t.strip_prefix("观看次数："));
        if let Some(rest) = rest {
            let parts: Vec<&str> = rest.split_whitespace().collect();
            if let Some(v) = parts.first() {
                d.views = v.replace('\u{a0}', "");
            }
            if let Some(date) = parts.last() {
                d.date = date.replace('\u{a0}', "");
            }
            break;
        }
    }

    // Localized title: the description panel renders
    //   <div ...>觀看次數：…</div><div style="margin-bottom: 5px">中文名</div>
    // right below the upload date. Unlocalized uploads repeat the original
    // title here, so callers compare the two before showing it.
    for panel in find_all(&dom, |h, p| has_class(p, h, "video-description-panel")) {
        // Direct children are laid out in reading order, so walk them in order
        // rather than descending (a stack walk would visit them reversed).
        let mut after_views = false;
        for c in children(parser, panel) {
            if has_class(parser, c, "video-caption-text") {
                continue;
            }
            let t = text(parser, c);
            if t.starts_with("觀看次數：") || t.starts_with("观看次数：") {
                after_views = true;
                continue;
            }
            if after_views && !t.is_empty() && t.chars().count() <= 120 {
                d.cn_title = t;
                break;
            }
        }
        if !d.cn_title.is_empty() {
            break;
        }
    }

    if let Some(h) = find_first(&dom, |h, p| attr(p, h, "id").as_deref() == Some("video-artist-name")) {
        d.author = text(parser, h);
    }

    // The genre link sits beside the artist name. The page also renders the
    // whole nav bar with `genre=` links, so skip anything nav-scoped and keep
    // the candidate closest to the artist block.
    let artist_pos = find_first(&dom, |h, p| attr(p, h, "id").as_deref() == Some("video-artist-name"))
        .and_then(|h| h.get(parser))
        .and_then(|n| n.as_tag().map(|t| t.boundaries(parser)))
        .map(|(s, _)| s);
    let mut best: Option<(i32, String)> = None;
    for a in find_all(&dom, |h, p| attr(p, h, "href").is_some_and(|v| v.contains("genre="))) {
        let nav = has_class(parser, a, "nav-item")
            || attr(parser, a, "class").is_some_and(|c| c.contains("nav-desktop") || c.contains("dropdown"));
        if nav {
            continue;
        }
        // The artist link itself carries `&genre=` in its query string, and
        // tag links look like `/search?tags[]=…&genre=…`; neither names the
        // category this video belongs to.
        if attr(parser, a, "id").as_deref() == Some("video-artist-name") {
            continue;
        }
        let href = attr(parser, a, "href").unwrap_or_default();
        // Only `/search?genre=X` (genre first) is the category chip.
        if !href.contains("?genre=") {
            continue;
        }
        let t = text(parser, a);
        if t.is_empty() || t.chars().count() > 24 {
            continue;
        }
        let pos = a
            .get(parser)
            .and_then(|n| n.as_tag().map(|x| x.boundaries(parser)))
            .map(|(s, _)| s as i64)
            .unwrap_or(i64::MAX);
        let dist = match artist_pos {
            Some(ap) => (pos - ap as i64).unsigned_abs().min(i32::MAX as u64) as i32,
            None => 0,
        };
        if best.as_ref().map(|(bd, _)| dist < *bd).unwrap_or(true) {
            best = Some((dist, t));
        }
    }
    if let Some((_, g)) = best {
        d.genre = g;
    }

    if let Some(h) = find_first(&dom, |h, p| attr(p, h, "id").as_deref() == Some("video-like-btn")) {
        // Inner text looks like "thumb_up93%  (60)": the material icon renders
        // as a ligature word, so keep only the numeric tail.
        let raw = text(parser, h);
        let t: String = raw
            .chars()
            .skip_while(|c| !c.is_ascii_digit())
            .collect();
        if let Some(pct) = t.find('%') {
            d.like_pct = t[..pct].trim().to_string() + "%";
            let rest = &t[pct + 1..];
            d.like_count = rest
                .chars()
                .filter(|c| c.is_ascii_digit())
                .collect::<String>();
        }
    }

    let keywords = meta_content(&dom, "keywords");
    d.tags = keywords
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .take(24)
        .collect();

    // Related videos reuse the rich horizontal card markup.
    let mut related = Vec::new();
    for c in find_all(&dom, |h, p| is_rich_card(p, h)) {
        if let Some(card) = parse_rich_card(&dom, c) {
            if card.id != id && related.len() < 24 {
                related.push(card);
            }
        }
    }
    d.related = related;

    if d.sources.is_empty() {
        return Err("no video source found".into());
    }
    Ok(d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_entities() {
        assert_eq!(unescape("a&nbsp;b"), "a b");
        assert_eq!(unescape("a&nbsp;&nbsp;b"), "a  b");
        assert_eq!(unescape("Lacey&amp;Xitzal"), "Lacey&Xitzal");
        assert_eq!(unescape("x &lt; y"), "x < y");
        assert_eq!(unescape("plain"), "plain");
        assert_eq!(unescape("觀看次數：1.3萬次&nbsp;&nbsp;2026-10-31"), "觀看次數：1.3萬次  2026-10-31");
    }
}
