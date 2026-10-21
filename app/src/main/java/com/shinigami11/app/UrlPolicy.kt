package com.shinigami11.app

/**
 * Decides what to do with a navigation request. Pure Kotlin (no Android classes)
 * so it can be unit-tested on the JVM.
 */
object UrlPolicy {
    const val HOST = "11.shinigami.asia"
    const val START_URL = "https://$HOST/"

    sealed interface Decision {
        /** Let the WebView load it. */
        data object Allow : Decision

        /** Same site over plain http: reload it over https instead. */
        data object UpgradeToHttps : Decision

        /** Hand it to another app (browser, mail, dialer). */
        data object OpenExternally : Decision

        /** Refuse it (javascript:, intent:, file:, content:, ...). */
        data object Block : Decision
    }

    fun isInternalHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == HOST || h.endsWith(".$HOST")
    }

    /**
     * @param isRedirect true when the navigation is a server-side redirect. HTTPS redirects
     * are followed inside the WebView so a site-side domain change does not break browsing.
     * Links the user taps that lead to other hosts open in the external browser.
     */
    fun decide(
        scheme: String?,
        host: String?,
        isMainFrame: Boolean,
        isRedirect: Boolean,
    ): Decision {
        // Sub-frames (e.g. embedded challenge/verification widgets) must load normally.
        if (!isMainFrame) return Decision.Allow

        return when (scheme?.lowercase()) {
            "https" ->
                if (isInternalHost(host) || isRedirect) Decision.Allow else Decision.OpenExternally
            "http" ->
                if (isInternalHost(host)) Decision.UpgradeToHttps else Decision.OpenExternally
            "mailto", "tel" -> Decision.OpenExternally
            else -> Decision.Block
        }
    }
}
