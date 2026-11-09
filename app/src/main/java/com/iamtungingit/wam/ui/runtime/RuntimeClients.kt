package com.iamtungingit.wam.ui.runtime

import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri

/**
 * WebViewClient wiring — delegates all decisions to RuntimeActivity (spec §9.4).
 */
class RuntimeWebViewClient(private val activity: RuntimeActivity) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return activity.handleNavigation(request.url)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return activity.interceptRequest(request)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        activity.onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        activity.onPageFinished()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            activity.onLoadError(
                error.description?.toString() ?: "unknown error",
                request.url.toString()
            )
        } else {
            // Subresource failures (a missing style.css / app.js / image) used to be
            // completely silent — the page then renders unstyled and the user just
            // sees a wrong-colored, half-dead screen with no hint why. Log every one
            // of them to the dev console so the cause is visible at a glance.
            activity.addConsoleLine(
                "ERR",
                "resource failed: ${error.description ?: "error"} · ${request.url}"
            )
        }
    }

    override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
        // Never proceed on SSL errors (spec §9.1.6).
        handler.cancel()
        activity.onLoadError("SSL error: ${error.primaryError}")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) {
            val reason = when (response.statusCode) {
                404 -> "HTTP 404 — the site has no page at this address"
                403 -> "HTTP 403 — the site refused access"
                in 500..599 -> "HTTP ${response.statusCode} — the site had an internal error"
                else -> "HTTP ${response.statusCode}"
            }
            activity.onLoadError(reason, request.url.toString())
        } else {
            // Same diagnosability rule as onReceivedError: a 403/404 on a subresource
            // (e.g. a path blocked by the policy) is logged to the dev console.
            activity.addConsoleLine(
                "ERR",
                "HTTP ${response.statusCode} · ${request.url}"
            )
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        activity.onRendererGone()
        return true
    }
}

/**
 * WebChromeClient wiring: progress, console, custom views (HTML5 fullscreen),
 * file chooser, permission denial (spec §9.2).
 */
class RuntimeChromeClient(private val activity: RuntimeActivity) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        activity.onProgress(newProgress)
    }

    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
        activity.addConsoleLine(
            consoleMessage.messageLevel()?.toString() ?: "LOG",
            "${consoleMessage.message()} (${consoleMessage.sourceId() ?: "?"}:${consoleMessage.lineNumber()})"
        )
        return true
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        activity.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        activity.onHideCustomView()
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        return activity.onFileChooser(filePathCallback, fileChooserParams)
    }

    override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
        // v1.5.0 per-app settings: camera/voice recorder are granted only when the
        // user enabled them for THIS app (App settings) and the Android runtime
        // permission is granted. Everything else stays denied (spec §9.2).
        activity.onWebPermissionRequest(request)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: android.webkit.GeolocationPermissions.Callback
    ) {
        callback.invoke(origin, false, false)
    }
}
