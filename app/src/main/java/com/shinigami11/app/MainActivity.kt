package com.shinigami11.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/** Single-screen WebView shell for https://11.shinigami.asia/ (no app-level login or signup). */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var errorDetail: TextView

    /** URL of the navigation currently loading; used by Retry. */
    private var currentPageUrl: String = UrlPolicy.START_URL
    private var sslFailureShown = false
    private var rendererGone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorTitle = findViewById(R.id.errorTitle)
        errorMessage = findViewById(R.id.errorMessage)
        errorDetail = findViewById(R.id.errorDetail)
        findViewById<Button>(R.id.retryButton).setOnClickListener { retry() }

        configureWebView()

        // Restore history after process death; otherwise open the website directly.
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(UrlPolicy.START_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        // Remote debugging only for debuggable (debug) builds, never in release.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, ALLOW_THIRD_PARTY_COOKIES)
        }

        webView.settings.apply {
            javaScriptEnabled = true // the site needs JavaScript
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            useWideViewPort = true
            builtInZoomControls = true // pinch-zoom for reading
            displayZoomControls = false
            // The default WebView user agent is intentionally left unchanged.
        }
        // No addJavascriptInterface: no JS bridge into the app.

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                return when (
                    UrlPolicy.decide(uri.scheme, uri.host, request.isForMainFrame, request.isRedirect)
                ) {
                    UrlPolicy.Decision.Allow -> false
                    UrlPolicy.Decision.UpgradeToHttps -> {
                        view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                        true
                    }
                    UrlPolicy.Decision.OpenExternally -> {
                        openExternally(uri)
                        true
                    }
                    UrlPolicy.Decision.Block -> true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) currentPageUrl = url
                sslFailureShown = false
                errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            // Main-frame load failures only. Failed images/scripts are ignored here.
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame || sslFailureShown) return
                showLoadError(error.errorCode, error.description?.toString())
            }

            // HTTP 4xx/5xx responses are NOT replaced by our error screen: the server's own
            // page (for example a verification page) stays visible and usable.

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                handler.cancel() // never proceed past a certificate error
                if (error.url == currentPageUrl) {
                    sslFailureShown = true
                    displayError(
                        R.string.error_ssl_title,
                        R.string.error_ssl_message,
                        "SSL error code ${error.primaryError}",
                    )
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                // The WebView is unusable now; rebuild the activity (and a fresh WebView)
                // instead of letting the whole app crash.
                rendererGone = true
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showLoadError(code: Int, description: String?) {
        // "Offline" is only claimed when Chromium itself reports it.
        val offline = description?.contains("ERR_INTERNET_DISCONNECTED") == true
        val (title, message) = when {
            offline -> R.string.error_offline_title to R.string.error_offline_message
            code == WebViewClient.ERROR_HOST_LOOKUP ->
                R.string.error_host_title to R.string.error_host_message
            code == WebViewClient.ERROR_TIMEOUT || code == WebViewClient.ERROR_CONNECT ->
                R.string.error_unreachable_title to R.string.error_unreachable_message
            code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
                R.string.error_ssl_title to R.string.error_ssl_message
            else -> R.string.error_generic_title to R.string.error_generic_message
        }
        displayError(title, message, description)
    }

    private fun displayError(titleRes: Int, messageRes: Int, detail: String?) {
        errorTitle.setText(titleRes)
        errorMessage.setText(messageRes)
        errorDetail.text = detail.orEmpty()
        progressBar.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun retry() {
        errorView.visibility = View.GONE
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        webView.loadUrl(currentPageUrl)
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // No app can handle this link; ignore.
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            errorView.visibility = View.GONE
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!rendererGone) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        /** Off by default (privacy). Set true only if the site's own sign-in/verification needs it. */
        const val ALLOW_THIRD_PARTY_COOKIES = false
    }
}
