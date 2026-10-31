package com.hanime.app

/** Browser identity strings shared by the image loader and any Kotlin-side HTTP. */
object Ua {
    /**
     * Must match the Rust core's UA: covers are signed against the page request,
     * and the CDN rejects mismatched agents on some edges.
     */
    const val BROWSER =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"
}
