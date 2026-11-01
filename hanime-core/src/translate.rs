//! Title translation.
//!
//! hanime1.me publishes collection titles in Japanese, which makes the download
//! folders unreadable for the Chinese-speaking audience the category names are
//! already localized for. The Google translate endpoint the site's own widgets use
//! takes a plain query string and answers with a JSON array; it needs no key, so it
//! is the only option that does not ship a secret in the APK.
//!
//! Translation is best-effort by design: a collection that fails to translate
//! keeps its original name rather than blocking the download.

use super::net;

/// Pulls the first translated segment out of `[[["译文","原文",...],...],...]`.
///
/// The response interleaves the translated and source strings per sentence, and a
/// long title arrives as several segments that have to be concatenated.
pub fn parse_gtx(json: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(json).ok()?;
    let segments = v.get(0)?.as_array()?;
    let mut out = String::new();
    for seg in segments {
        // Each entry is itself an array whose first element is the translation.
        if let Some(t) = seg.get(0).and_then(|s| s.as_str()) {
            out.push_str(t);
        }
    }
    let out = out.trim().to_string();
    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}

/// True when the text has no Han characters a Chinese reader would recognize.
fn needs_translation(s: &str) -> bool {
    !s.chars().any(|c| ('\u{4e00}'..='\u{9fff}').contains(&c))
}

/// Translates to Simplified Chinese, returning the input unchanged on any failure.
///
/// `sl=auto` because collection titles mix Japanese, romaji and English, and
/// pinning the source language mistranslates the mixed ones.
pub fn translate_zh(text: &str) -> String {
    let text = text.trim();
    if text.is_empty() || !needs_translation(text) {
        return text.to_string();
    }
    let url = format!(
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q={}",
        net::percent_encode(text)
    );
    match net::get_json(&url) {
        Ok(body) => parse_gtx(&body).unwrap_or_else(|| text.to_string()),
        Err(_) => text.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn concatenates_segments() {
        let json = r#"[[["OVA 饲养员姐妹","OVA シスターブリーダー",null,null,3]],null,"ja"]"#;
        assert_eq!(parse_gtx(json).as_deref(), Some("OVA 饲养员姐妹"));
    }

    #[test]
    fn keeps_already_chinese_titles() {
        assert_eq!(translate_zh("姐妹調教飼育者"), "姐妹調教飼育者");
        assert_eq!(translate_zh(""), "");
    }
}
