package com.mccal.folio

/** Where a typed query can go. Google uses the "Web" filter (udm=14), which omits AI Overviews. */
internal enum class WebSearchTarget(val label: String, private val prefix: String,
    /** Fold8Duo: an app that takes the query as shared text (the prompt), used instead of the link when installed. */
    val sharesTo: String? = null) {
    CLAUDE("Ask Claude", "https://claude.ai/new?q=", sharesTo = com.mccal.folio.duo.Assistant.CLAUDE),   // Fold8Duo (WP-71): first, the owner's pick
    GEMINI("Ask Gemini", "https://gemini.google.com/app?q=", sharesTo = com.mccal.folio.duo.Assistant.GEMINI), // Fold8Duo (WP-71): a brand name // english-only
    GOOGLE("Google", "https://www.google.com/search?udm=14&q="),
    DUCKDUCKGO("DuckDuckGo", "https://noai.duckduckgo.com/?q="),
    CHATGPT("Ask ChatGPT", "https://chatgpt.com/?q="),
    PERPLEXITY("Perplexity", "https://www.perplexity.ai/search?q=");

    fun uri(query: String): android.net.Uri = android.net.Uri.parse(prefix + android.net.Uri.encode(query.trim()))
}

internal fun openWebSearch(context: android.content.Context, target: WebSearchTarget, query: String) {
    // Fold8Duo (WP-71): Claude and Gemini take the query as shared text, which becomes the prompt (duo/Gemini.kt).
    if (target.sharesTo != null && com.mccal.folio.duo.Assistant.installed(context, target.sharesTo) &&
        com.mccal.folio.duo.Assistant.share(context, target.sharesTo, query)) return
    // A plain https link: the matching app opens it if installed and verified, otherwise the browser.
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, target.uri(query))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

