package com.iamtungingit.wam.ui.runtime

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iamtungingit.wam.R
import com.iamtungingit.wam.BuildConfig
import com.iamtungingit.wam.app.WamApp
import com.iamtungingit.wam.databinding.ActivityRuntimeBinding
import com.iamtungingit.wam.ui.common.Ui
import com.iamtungingit.wam.util.Hashing
import com.iamtungingit.wam.util.JsonUtil
import com.iamtungingit.wam.util.MimeTypes
import com.iamtungingit.wam.util.PathSanitizer
import com.iamtungingit.wam.wap.bridge.BridgeHost
import com.iamtungingit.wam.wap.bridge.WapDataStore
import com.iamtungingit.wam.wap.bridge.WapFeatures
import com.iamtungingit.wam.wap.manifest.UrlValidator
import com.iamtungingit.wam.wap.manifest.WapManifest
import com.iamtungingit.wam.wap.archive.SafeExtractor
import com.iamtungingit.wam.wap.archive.ZipValidator
import com.iamtungingit.wam.wap.runtime.IntentOverrideResolver
import com.iamtungingit.wam.wap.runtime.LaunchResolver
import com.iamtungingit.wam.wap.runtime.LocalPreviewServer
import com.iamtungingit.wam.wap.runtime.NetworkPolicy
import com.iamtungingit.wam.wap.runtime.SessionManager
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Runs any WAP in a secured WebView (spec §9, §10). Non-exported; only WAM-created
 * Intents reach it, and every extra is re-validated (defense in depth).
 */
class RuntimeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRuntimeBinding
    private val app get() = WamApp.instance

    private var plan: LaunchResolver.LaunchPlan? = null
    private var session: SessionManager.ActiveSession? = null
    private var networkAllowed = false
    private var directZip = false
    private var zipHandler: ZipHandler? = null
    private var webView: WebView? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileChooserCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pendingDownloadUrl: String? = null
    private val consoleBuffer = ArrayDeque<String>()
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null

    // ---- Per-app permission enforcement (v1.5.0 user request #7) ----
    // Camera / voice recorder grant through the WebView permission prompt gated by
    // App settings; "send data" blocks non-GET network requests; TTS is gated in
    // the bridge. All defaults are OFF (least privilege).
    private var bridgeHost: BridgeHost? = null
    private var pendingWebPermission: android.webkit.PermissionRequest? = null

    // ---- Official JS API (v1.6.0 user request #3): camera / mic / net.send native side.
    private var features: WapFeatures? = null

    private val apiCameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            features?.onCameraResult(ok)
        }

    private val apiMicPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            features?.onMicPermissionResult(granted)
        }

    // ---- Browser engine (user request: "use chromium or firefox instead of webview")
    // Default engine: the project is served over 127.0.0.1 by LocalPreviewServer and
    // opened in the device's REAL browser (Chrome = Chromium, or Firefox). The in-app
    // WebView kept painting solid black on the target device; a real browser cannot.
    private var previewServer: LocalPreviewServer? = null
    private var handoffUrl: String? = null

    // ---- Never-black-screen hardening (user report: "still black, even example.com") ----
    // Every previous failure mode (a swallowed exception in the launch chain, a dead
    // renderer, a GPU compositing bug) left the activity on the dark loading view with
    // zero explanation. Now: every failure becomes a visible error screen, a watchdog
    // catches pages that never finish, and a first-paint check detects the classic
    // "solid black frame" GPU bug and auto-recovers with a software-rendered WebView.
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pageFinishedSeen = false
    private var loadStartedAt = 0L
    private var lastLoadedUrl: String? = null
    private var softwareFallbackUsed = false
    private var paintChecked = false
    private var openBrowserUrl: String? = null
    private val paintCheckRunnable = Runnable { checkFirstPaint() }
    private val watchdogRunnable = Runnable { onLoadWatchdogFired() }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileChooserCallback
            fileChooserCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        }

    private val downloadLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val url = pendingDownloadUrl
            pendingDownloadUrl = null
            if (uri != null && url != null) startDownload(url, uri)
        }

    private val webPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingWebPermission
            pendingWebPermission = null
            if (request == null) return@registerForActivityResult
            val wanted = HashSet<String>(request.resources.toList())
            val grant = ArrayList<String>()
            if (wanted.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                grants[android.Manifest.permission.CAMERA] == true
            ) grant.add(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            if (wanted.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                grants[android.Manifest.permission.RECORD_AUDIO] == true
            ) grant.add(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (grant.isEmpty()) request.deny() else request.grant(grant.toTypedArray())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuntimeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applyInsets(binding.runtimeRoot)

        binding.btnRetry.setOnClickListener { retryLoad() }
        binding.btnClose.setOnClickListener { finish() }
        binding.btnDiag.setOnClickListener { toggleConsole() }
        binding.btnOpenBrowser.setOnClickListener { openInBrowser() }
        binding.btnHandoffOpen.setOnClickListener { openHandoffInBrowser() }
        binding.btnHandoffBuiltin.setOnClickListener { switchToBuiltInViewer() }
        binding.btnHandoffClose.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (binding.handoffView.visibility == View.VISIBLE) {
                    finish()
                } else if (customView != null) {
                    onHideCustomView()
                } else if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else if (app.settings.current.confirmExit && plan?.isPreview != true) {
                    confirmExitDialog()
                } else {
                    finish()
                }
            }
        })

        resolveAndLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop: reuse this instance for the new WAP so launching apps or previews
        // never adds new entries to Recents (user-reported issue 3).
        teardownRuntime()
        resolveAndLaunch(intent)
    }

    /** Destroys the current WebView/session without finishing the activity. */
    private fun teardownRuntime() {
        activeDialog?.dismiss()
        activeDialog = null
        cancelLoadWatchdog()
        uiHandler.removeCallbacks(paintCheckRunnable)
        stopPreviewServer()
        pageFinishedSeen = false
        paintChecked = false
        lastLoadedUrl = null
        openBrowserUrl = null
        val wv = webView
        webView = null
        if (wv != null) {
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        session?.let { app.sessionManager.endSession(it.sessionId) }
        session = null
        zipHandler?.close()
        zipHandler = null
        plan = null
        networkAllowed = false
        bridgeAttached = false
        assetHandlerRoot = null
        bridgeHost?.shutdown()
        bridgeHost = null
        features?.shutdown()
        features = null
        consoleBuffer.clear()
        binding.consoleText.text = ""
        binding.consoleOverlay.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.handoffView.visibility = View.GONE
        binding.handoffError.visibility = View.GONE
        binding.loadingView.visibility = View.VISIBLE
        binding.loadingText.text = getString(R.string.loading_please_wait)
    }

    /** Stops the localhost preview server (browser engine) if one is running. */
    private fun stopPreviewServer() {
        previewServer?.stop()
        previewServer = null
        handoffUrl = null
    }

    // ------------------------------------------------------------- launch flow

    private fun resolveAndLaunch(intent: Intent) {
        binding.errorView.visibility = View.GONE
        binding.loadingView.visibility = View.VISIBLE
        binding.loadingText.text = getString(R.string.loading_please_wait)
        // onError: any exception in the launch chain (manifest probe, resolver, IO)
        // used to be swallowed silently — the screen stayed black with no explanation.
        Ui.runIO(onError = { t -> Ui.onMain { showLaunchError(t) } }) {
            val id = intent.getStringExtra(IntentOverrideResolver.EXTRA_ID)
            val widgetIdRaw = intent.getIntExtra(IntentOverrideResolver.EXTRA_WIDGET_ID, -1)
            val widgetId = if (widgetIdRaw >= 0) widgetIdRaw else null
            val previewPath = intent.getStringExtra(IntentOverrideResolver.EXTRA_PREVIEW_PATH)
            val previewProject = intent.getStringExtra(EXTRA_PREVIEW_PROJECT)

            val rec = if (widgetId != null) app.widgetStore.get(widgetId) else null
            val widgetUsable = rec != null && rec.wapId == id && id != null
            val widgetWapId = rec?.wapId
            val widgetNet = rec?.network

            val entryType = if (previewPath != null) "offline"
            else app.registry.get(id ?: "")?.type ?: "offline"

            // Manifest probe for entry allow-list + type consistency.
            val probe: com.iamtungingit.wam.wap.manifest.ManifestParser.Result = when {
                previewPath != null ->
                    ManifestParserHelper.fromFile(File(previewPath), app.settings.current.maxQuotaBytes)
                id != null && PathSanitizer.isValidId(id) ->
                    com.iamtungingit.wam.wap.manifest.ManifestParser.fromArchive(
                        com.iamtungingit.wam.storage.Paths.archiveFile(applicationContext, id),
                        app.settings.current.maxQuotaBytes
                    )
                else -> com.iamtungingit.wam.wap.manifest.ManifestParser.Result.Failure(
                    com.iamtungingit.wam.wap.manifest.ValidationError(
                        com.iamtungingit.wam.wap.manifest.ErrorCodes.ID_INVALID, "WAP_ID", "missing or invalid")
                )
            }
            val probeManifest = (probe as? com.iamtungingit.wam.wap.manifest.ManifestParser.Result.Success)?.manifest
            val isOffline = probeManifest?.isOffline ?: (entryType == "offline")
            val allowedEntries = probeManifest?.effectiveAllowedEntries?.toSet() ?: emptySet()

            val overrides = IntentOverrideResolver.resolve(
                id = id,
                entry = if (widgetUsable) rec?.entry else intent.getStringExtra(IntentOverrideResolver.EXTRA_ENTRY),
                mode = intent.getStringExtra(IntentOverrideResolver.EXTRA_MODE),
                fullscreen = if (widgetUsable) rec?.fullscreen else intent.extras?.get(IntentOverrideResolver.EXTRA_FULLSCREEN),
                theme = if (widgetUsable) rec?.theme else intent.getStringExtra(IntentOverrideResolver.EXTRA_THEME),
                network = if (widgetUsable && rec?.network != NetworkPolicy.INHERIT) rec?.network
                else intent.getStringExtra(IntentOverrideResolver.EXTRA_ALLOW_NETWORK),
                widgetId = widgetId,
                widgetWapId = widgetWapId,
                isOffline = isOffline,
                entryAllowed = { path -> path in allowedEntries }
            )

            if (overrides.dropped.isNotEmpty()) {
                overrides.dropped.forEach { android.util.Log.w("WAM", "dropped override: $it") }
            }

            when (val r = LaunchResolver.resolve(
                applicationContext, app.registry, app.settings, overrides,
                previewPath, previewProject, widgetNet
            )) {
                is LaunchResolver.Result.Fail -> Ui.onMain { showError(r.message, retryable = false) }
                is LaunchResolver.Result.Ok -> Ui.onMain { onPlanReady(r.plan) }
            }
        }
    }

    private fun onPlanReady(p: LaunchResolver.LaunchPlan) {
        plan = p

        // Network policy gate (spec §9.5): prompt BEFORE loading anything.
        when {
            p.networkPolicy == NetworkPolicy.DENY && !p.manifest.isOffline ->
                showNetworkDeniedDialog(p)
            p.networkPolicy == NetworkPolicy.ASK ->
                showNetworkAskDialog(p)
            else -> {
                networkAllowed = p.networkPolicy == NetworkPolicy.ALLOW
                proceedLoad()
            }
        }
    }

    private fun showNetworkAskDialog(p: LaunchResolver.LaunchPlan) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.runtime_net_prompt_title, p.manifest.name))
            .setItems(
                arrayOf(
                    getString(R.string.runtime_btn_allow_once),
                    getString(R.string.runtime_btn_allow_always),
                    getString(R.string.runtime_btn_deny_once),
                    getString(R.string.runtime_btn_deny_always)
                )
            ) { _, which ->
                when (which) {
                    0 -> { networkAllowed = true; proceedLoad() }
                    1 -> { saveNetworkPref("allow"); networkAllowed = true; proceedLoad() }
                    2 -> { networkAllowed = false; proceedLoad() }
                    3 -> { saveNetworkPref("deny"); networkAllowed = false; proceedLoad() }
                }
            }
            .setOnCancelListener { finish() }
            .show()
            .also { activeDialog = it }
    }

    private fun showNetworkDeniedDialog(p: LaunchResolver.LaunchPlan) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.runtime_err_network_denied)
            .setMessage(getString(R.string.runtime_net_prompt_title, p.manifest.name))
            .setPositiveButton(R.string.runtime_btn_allow_once) { _, _ ->
                networkAllowed = true; proceedLoad()
            }
            .setNeutralButton(R.string.runtime_btn_allow_always) { _, _ ->
                saveNetworkPref("allow"); networkAllowed = true; proceedLoad()
            }
            .setNegativeButton(R.string.action_close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
            .also { activeDialog = it }
    }

    private fun saveNetworkPref(value: String) {
        val wapId = plan?.wapId ?: return
        if (plan?.isPreview == true) return
        Ui.runIO { app.installManager.setNetworkPref(wapId, value) }
    }

    private fun proceedLoad() {
        val p = plan ?: return
        if (!p.manifest.isOffline && !networkAllowed) {
            showError(getString(R.string.runtime_err_network_denied), retryable = true)
            return
        }
        val useBrowserEngine = app.settings.current.previewEngine == "browser"
        // onError: extraction/session failures must surface, never linger as a
        // frozen loading screen (user-reported "it said installing … and went black").
        Ui.runIO(onError = { t -> Ui.onMain { showLaunchError(t) } }) {
            val settings = app.settings.current
            // directZip serves straight from the archive inside the WebView path;
            // the browser engine always uses an extracted session (simpler + safer).
            directZip = settings.directZip && p.manifest.isOffline && !useBrowserEngine
            if (directZip) {
                zipHandler = ZipHandler(p.archive)
                session = null
            } else {
                val s = app.sessionManager.createSession(p.dataId)
                if (s == null) {
                    Ui.onMain { showError("cannot create session", retryable = true) }
                    return@runIO
                }
                session = s
                val ex = SafeExtractor.extractAll(p.archive, s.contentDir)
                if (!ex.ok) {
                    app.sessionManager.endSession(s.sessionId)
                    session = null
                    Ui.onMain { showError(ex.error?.toString() ?: "extraction failed", retryable = true) }
                    return@runIO
                }
            }
            Ui.onMain {
                if (useBrowserEngine) launchInBrowser() else setupWebViewAndLoad()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebViewAndLoad() {
        val p = plan ?: return
        applyDisplaySettings(p)
        binding.btnDiag.visibility =
            if (app.settings.current.devConsole) View.VISIBLE else View.GONE
        try {
            val wv = createWebView(p)
            binding.runtimeRoot.addView(
                wv, 0,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            webView = wv

            val contentDir = session?.contentDir
            assetHandlerRoot = contentDir

            pageFinishedSeen = false
            paintChecked = false
            // Dev console (user request): purely opt-in via Settings → Developer;
            // previews no longer force it open. Errors still auto-open it.
            if (app.settings.current.devConsole) {
                binding.consoleOverlay.visibility = View.VISIBLE
                addConsoleLine("SYS", "session start · id=${p.wapId} · network=${if (networkAllowed) "allowed" else "denied"}")
                addConsoleLine("SYS", webviewDiagnostics())
            }

            // Restricted bridge (spec D11) — only for the WAP's own origin.
            val store = WapDataStore.forId(applicationContext, p.dataId, p.manifest.quotaBytes)
            if (store != null) {
                val prefs = app.appPrefs.get(p.wapId)
                // Official JS API (v1.6.0): WAP.camera / WAP.mic / WAP.net / WAP.permissions,
                // each gated by this app's permission toggles in App settings.
                features = WapFeatures(
                    activity = this,
                    wapId = p.wapId,
                    manifest = p.manifest,
                    offline = p.manifest.isOffline,
                    sessionDir = session?.contentDir,
                    dataStore = store,
                    prefs = { app.appPrefs.get(p.wapId) },
                    networkAllowed = { networkAllowed },
                    cameraLauncher = apiCameraLauncher,
                    micPermissionLauncher = apiMicPermissionLauncher,
                    log = { line -> addConsoleLine("API", line) }
                )
                val host = BridgeHost(
                    wv, p.domain, p.wapId, p.manifest, store, networkAllowed,
                    ttsAllowed = prefs.tts,
                    onTtsBlocked = {
                        addConsoleLine("SEC", "tts blocked — enable Text-to-speech for this app in App settings")
                    },
                    features = features
                ) {
                    finish()
                }
                host.attach()
                bridgeAttached = host.supported
                bridgeHost = host
            }

            binding.loadingView.visibility = View.VISIBLE
            binding.loadingText.text = getString(R.string.runtime_loading, p.manifest.name)

            val url = if (p.manifest.isOffline) {
                "https://${p.domain}/${p.entry}${p.entryQueryFragment}"
            } else {
                buildOnlineUrl(p)
            }
            lastLoadedUrl = url
            wv.loadUrl(url)
            startLoadWatchdog()
        } catch (t: Throwable) {
            // WebView creation/bridge failures (e.g. a device without a working WebView
            // provider) used to propagate into Ui.onMain's catch-all and leave a black
            // screen. Show exactly what broke instead.
            showWebViewFailure(t)
        }
    }

    /**
     * Builds the URL for online WAPs.
     *
     * Fixed (user-reported "online shows black screen / couldn't run"): the manifest
     * entry of an online WAP is a FULL URL (UrlValidator enforces a scheme), so
     * p.entry itself contains "://" — the old code treated it as a path and glued it
     * onto the origin again, producing "https://example.comhttps://example.com".
     */
    private fun buildOnlineUrl(p: LaunchResolver.LaunchPlan): String {
        val entry = p.entry
        val qf = p.entryQueryFragment
        // Full-URL entry (the normal case for online WAPs) — load it as-is.
        if (entry.contains("://")) return entry + qf
        // Path-only entry (e.g. a widget/entry override): resolve it against the
        // origin of the manifest's entry URL.
        val base = Uri.parse(p.manifest.entry)
        val scheme = base.scheme
        val host = base.host
        if (scheme.isNullOrEmpty() || host.isNullOrEmpty()) return entry + qf
        val port = if (base.port != -1) ":${base.port}" else ""
        val prefix = (base.path ?: "").trimEnd('/')
        val path = if (entry.startsWith("/")) entry else "$prefix/$entry"
        return "$scheme://$host$port$path$qf"
    }

    // ------------------------------------------------------- browser engine

    /**
     * Engine "browser" (the new default, user request): hand the project to the
     * device's real browser instead of the in-app WebView.
     *  - Offline WAPs: LocalPreviewServer serves the extracted files on
     *    http://127.0.0.1:<port>/p/<token>/… and we open that URL — Chrome
     *    (Chromium) or Firefox renders it, so the WebView black-screen bug is
     *    bypassed entirely. The same CSP/network policy is enforced via headers.
     *  - Online WAPs: the manifest entry is a full URL — open it directly.
     * The hand-off screen itself is plain Views and can never render black.
     */
    private fun launchInBrowser() {
        val p = plan ?: return
        cancelLoadWatchdog()
        binding.loadingView.visibility = View.GONE
        binding.pageProgress.visibility = View.GONE
        addConsoleLine("SYS", "engine=browser · handing off to the device browser")

        val url: String = if (p.manifest.isOffline) {
            val dir = session?.contentDir
            if (dir == null) {
                showError(getString(R.string.runtime_err_generic, "no session content directory"), retryable = true)
                return
            }
            if (previewServer == null) {
                val srv = LocalPreviewServer(
                    root = dir,
                    networkAllowed = networkAllowed,
                    allowedHosts = p.manifest.allowedHosts,
                    log = { line -> addConsoleLine("SRV", line) }
                )
                if (!srv.start()) {
                    showError(getString(R.string.runtime_err_generic, "local preview server failed to start"), retryable = true)
                    return
                }
                previewServer = srv
            }
            previewServer!!.urlFor(p.entry + p.entryQueryFragment)
        } else {
            buildOnlineUrl(p)
        }
        handoffUrl = url
        showHandoff()
        openHandoffInBrowser()
    }

    private fun showHandoff() {
        val name = plan?.manifest?.name ?: "app"
        binding.handoffMsg.text = getString(R.string.runtime_handoff_msg, name)
        binding.handoffUrl.text = handoffUrl ?: ""
        binding.handoffError.visibility = View.GONE
        binding.handoffView.visibility = View.VISIBLE
        binding.btnDiag.visibility =
            if (app.settings.current.devConsole || consoleBuffer.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Fires ACTION_VIEW for the hand-off URL; a missing browser is reported on-screen. */
    private fun openHandoffInBrowser() {
        val url = handoffUrl ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            binding.handoffError.text = getString(R.string.runtime_handoff_no_browser)
            binding.handoffError.visibility = View.VISIBLE
            addConsoleLine("ERR", "no browser app available for $url")
        } catch (e: Exception) {
            binding.handoffError.text = getString(R.string.runtime_err_generic, e.message ?: "open failed")
            binding.handoffError.visibility = View.VISIBLE
            addConsoleLine("ERR", "browser launch failed: ${e.message}")
        }
    }

    /** Hand-off → built-in viewer for this launch only (does not change Settings). */
    private fun switchToBuiltInViewer() {
        stopPreviewServer()
        binding.handoffView.visibility = View.GONE
        binding.handoffError.visibility = View.GONE
        setupWebViewAndLoad()
    }

    private var assetHandlerRoot: File? = null
    private var bridgeAttached = false

    private fun createWebView(p: LaunchResolver.LaunchPlan): WebView {
        val wv = WebView(this)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.allowFileAccess = false
        @Suppress("DEPRECATION")
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.blockNetworkLoads = !networkAllowed
        s.setSupportMultipleWindows(false)
        @Suppress("DEPRECATION")
        s.setGeolocationEnabled(false)
        try {
            s.safeBrowsingEnabled = app.settings.current.safeBrowsing
        } catch (e: Exception) {
            // Feature missing on this WebView build.
        }
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = true
        s.textZoom = app.settings.current.textZoom
        // "Force dark" setting (spec §15): on Android 13+ the deprecated
        // WebSettings.forceDark API became a NO-OP, so on Android 16 devices (Redmi
        // 14C) the toggle silently did nothing. The androidx.webkit algorithmic
        // darkening API is the supported replacement and actually darkens light
        // pages when the user enables the setting.
        try {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                s, app.settings.current.forceDark
            )
        } catch (e: Throwable) {
            @Suppress("DEPRECATION")
            if (app.settings.current.forceDark) s.forceDark = WebSettings.FORCE_DARK_ON
        }
        // Opaque base background (user report: "I see black instead of the project"):
        // the old TRANSPARENT base let the dark Material Night activity background
        // show through wherever the page had not painted yet — first frame, unstyled
        // HTML, a failed stylesheet — so any hiccup looked like a dead black screen.
        // WHITE is what every browser paints before the page's own colors arrive;
        // a manifest backgroundColor still wins when the WAP declares one.
        wv.setBackgroundColor(
            p.manifest.backgroundColor?.let { JsonUtil.parseColorHex(it) } ?: Color.WHITE
        )
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG || app.settings.current.webViewDebug)
        wv.webViewClient = RuntimeWebViewClient(this)
        wv.webChromeClient = RuntimeChromeClient(this)
        wv.setDownloadListener { url, _, _, _, _ -> onDownloadRequested(url) }
        return wv
    }

    // ------------------------------------------------------- never-black hardening

    private fun showLaunchError(t: Throwable) {
        addConsoleLine("ERR", "launch failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        showError(
            getString(
                R.string.runtime_err_generic,
                "${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            ),
            retryable = true
        )
    }

    private fun showWebViewFailure(t: Throwable) {
        addConsoleLine("ERR", "WebView failure: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        addConsoleLine("SYS", webviewDiagnostics())
        val providerBroken = t.message?.contains("provider", ignoreCase = true) == true
        showError(
            if (providerBroken) getString(R.string.runtime_err_webview_missing)
            else getString(R.string.runtime_err_generic, "${t.javaClass.simpleName}: ${t.message ?: "unknown"}"),
            retryable = true
        )
    }

    private fun startLoadWatchdog() {
        loadStartedAt = System.currentTimeMillis()
        uiHandler.removeCallbacks(watchdogRunnable)
        uiHandler.postDelayed(watchdogRunnable, LOAD_WATCHDOG_MS)
    }

    private fun cancelLoadWatchdog() {
        uiHandler.removeCallbacks(watchdogRunnable)
    }

    /** Fires when the page produced no onPageFinished within the watchdog window. */
    private fun onLoadWatchdogFired() {
        if (pageFinishedSeen) return
        // Still downloading (progress moving)? Keep waiting instead of faking an error.
        if (binding.pageProgress.visibility == View.VISIBLE &&
            binding.pageProgress.progress in 1..99
        ) {
            startLoadWatchdog()
            return
        }
        val wv = webView
        addConsoleLine("SYS", webviewDiagnostics())
        showError(
            if (wv == null) getString(R.string.runtime_err_watchdog_noview)
            else getString(
                R.string.runtime_err_watchdog,
                (System.currentTimeMillis() - loadStartedAt) / 1000
            ),
            retryable = true,
            url = lastLoadedUrl
        )
    }

    /**
     * First-paint sanity check, scheduled ~1.5 s after the first onPageFinished.
     * A healthy WebView paints at least text/padding — never a perfectly uniform,
     * near-black frame. That signature is a GPU/compositing bug seen on some budget
     * devices (user report: even example.com "shows black"). The fix that works on
     * them is re-creating the WebView with software rendering.
     */
    private fun checkFirstPaint() {
        if (paintChecked) return
        paintChecked = true
        val wv = webView ?: return
        val uniformBlack = try {
            val bmp = snapshotOf(wv) ?: return
            looksUniformBlack(bmp)
        } catch (t: Throwable) {
            false
        }
        if (!uniformBlack) return
        addConsoleLine("SYS", "first paint is a solid black frame — GPU/compositing bug suspected")
        if (!softwareFallbackUsed) {
            switchToSoftwareRendering()
        } else {
            showRendererBrokenError()
        }
    }

    private fun snapshotOf(wv: WebView): Bitmap? {
        val w = minOf(wv.width, 240).coerceAtLeast(1)
        val h = minOf(wv.height, 240).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        wv.draw(canvas)
        return bmp
    }

    private fun looksUniformBlack(bmp: Bitmap): Boolean {
        var minL = 255
        var maxL = 0
        var sum = 0L
        var n = 0
        val stepX = (bmp.width / 16).coerceAtLeast(1)
        val stepY = (bmp.height / 16).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (l < minL) minL = l
                if (l > maxL) maxL = l
                sum += l
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return false
        return (sum / n) < 24 && (maxL - minL) <= 8
    }

    /** Recreates the WebView with LAYER_TYPE_SOFTWARE (fixes GPU-compositing black frames). */
    private fun switchToSoftwareRendering() {
        softwareFallbackUsed = true
        val p = plan ?: return
        val old = webView
        webView = null
        if (old != null) {
            old.stopLoading()
            (old.parent as? ViewGroup)?.removeView(old)
            old.destroy()
        }
        addConsoleLine("SYS", "recreating WebView in software-rendering mode (compatibility)")
        Toast.makeText(this, R.string.runtime_compat_mode, Toast.LENGTH_LONG).show()
        try {
            val wv = createWebView(p)
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            binding.runtimeRoot.addView(
                wv, 0,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            webView = wv
            pageFinishedSeen = false
            paintChecked = false
            val url = lastLoadedUrl ?: return
            wv.loadUrl(url)
            startLoadWatchdog()
        } catch (t: Throwable) {
            showWebViewFailure(t)
        }
    }

    private fun showRendererBrokenError() {
        addConsoleLine("SYS", webviewDiagnostics())
        showError(getString(R.string.runtime_err_renderer_broken), retryable = true, url = lastLoadedUrl)
    }

    /** One-line device/WebView summary, always added to the console before errors. */
    private fun webviewDiagnostics(): String {
        val wv = webView
        val pkg = try {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
        } catch (t: Throwable) {
            null
        } ?: "unknown"
        val dm = resources.displayMetrics
        val bucket = when {
            dm.densityDpi <= 120 -> "ldpi"
            dm.densityDpi <= 160 -> "mdpi"
            dm.densityDpi <= 213 -> "tvdpi"
            dm.densityDpi <= 240 -> "hdpi"
            dm.densityDpi <= 280 -> "hdpi(280)"
            dm.densityDpi <= 320 -> "xhdpi"
            dm.densityDpi <= 420 -> "xhdpi(400)"
            dm.densityDpi <= 480 -> "xxhdpi"
            else -> "xxxhdpi"
        }
        return "diagnostics: webview=$pkg · hwAccel=${wv?.isHardwareAccelerated} · " +
            "screen=${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}dpi($bucket) · " +
            "net=${if (networkAllowed) "allowed" else "blocked"} · url=$lastLoadedUrl"
    }

    private fun toggleConsole() {
        val show = binding.consoleOverlay.visibility != View.VISIBLE
        binding.consoleOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) binding.consoleText.text = consoleBuffer.joinToString("\n")
    }

    private fun openInBrowser() {
        val u = openBrowserUrl ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.runtime_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyDisplaySettings(p: LaunchResolver.LaunchPlan) {
        when (p.orientation) {
            "portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "sensor" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> {}
        }
        if (p.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val bg = p.manifest.backgroundColor?.let { JsonUtil.parseColorHex(it) }
        if (bg != null) {
            binding.runtimeRoot.setBackgroundColor(bg)
        }
        WindowCompat.getInsetsController(window, binding.runtimeRoot)
            .isAppearanceLightStatusBars = p.theme == "light"
        applyFullscreen(p.fullscreen)
    }

    private fun applyFullscreen(enable: Boolean) {
        val controller = WindowCompat.getInsetsController(window, binding.runtimeRoot)
        if (enable) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------- navigation

    fun handleNavigation(url: Uri): Boolean {
        val p = plan ?: return true
        val scheme = url.scheme?.lowercase() ?: return true
        val host = url.host?.lowercase()

        if (p.manifest.isOffline) {
            if (scheme == "https" && host == p.domain) return false
        } else {
            val entryHost = Uri.parse(p.manifest.entry).host?.lowercase()
            if (scheme == "https" && host != null &&
                (host == entryHost || UrlValidator.hostAllowed(host, p.manifest.allowedHosts))
            ) return false
            if (scheme == "http" && app.settings.current.cleartextLocalhost && networkAllowed &&
                host in setOf("localhost", "127.0.0.1", "10.0.2.2")
            ) return false
        }

        when (scheme) {
            "http", "https", "mailto", "tel" -> {
                confirmExternal(url)
                return true
            }
            else -> {
                // intent:/file:/content:/javascript:/data: top-level navigations are blocked (spec §9.4).
                addConsoleLine("SEC", "blocked navigation: $scheme:")
                return true
            }
        }
    }

    private fun confirmExternal(url: Uri) {
        val p = plan ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.runtime_external_title)
            .setMessage(getString(R.string.runtime_external_msg, p.manifest.name, url.toString()))
            .setPositiveButton(R.string.runtime_btn_open_browser) { _, _ ->
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, url)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.runtime_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------- requests

    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val p = plan ?: return blockResponse(403)
        val url = request.url
        val host = url.host?.lowercase()
        val allowedHost = when {
            p.manifest.isOffline -> host == p.domain
            else -> {
                val entryHost = Uri.parse(p.manifest.entry).host?.lowercase()
                host != null && (host == entryHost || UrlValidator.hostAllowed(host, p.manifest.allowedHosts))
            }
        }
        if (!allowedHost) return blockResponse(403)

        // "Requests to send data" per-app permission (v1.5.0, marked dangerous in
        // App settings): non-GET requests (POST/PUT/PATCH/DELETE — uploads, form
        // submissions, API writes) are blocked unless the user granted it.
        val method = request.method?.uppercase() ?: "GET"
        if (networkAllowed && method != "GET") {
            val prefs = plan?.wapId?.let { app.appPrefs.get(it) }
            if (prefs?.sendData != true) {
                addConsoleLine("SEC", "blocked data request ($method): $url")
                return blockResponse(403)
            }
        }

        if (p.manifest.isOffline) {
            return if (directZip) {
                zipHandler?.serve(url) ?: blockResponse(404)
            } else {
                serveSessionFile(url, request)
            }
        }
        return null // online: let the network stack handle allowed hosts
    }

    private fun serveSessionFile(url: Uri, request: WebResourceRequest): WebResourceResponse? {
        val root = assetHandlerRoot?.canonicalFile ?: return blockResponse(404)
        val raw = url.path?.removePrefix("/") ?: return blockResponse(404)
        val rel = raw.substringBefore('?').substringBefore('#')
        if (rel.isEmpty()) return blockResponse(404)
        val norm = PathSanitizer.sanitizeRelativePath(rel) ?: return blockResponse(404)

        val f = try {
            File(root, norm).canonicalFile
        } catch (e: Exception) {
            return blockResponse(404)
        }
        if (!f.path.startsWith(root.path + File.separator) || !f.isFile) return blockResponse(404)

        val mime = MimeTypes.fromPath(norm)
        val encoding = if (MimeTypes.isText(mime)) "UTF-8" else null
        val headers = HashMap<String, String>()
        headers["X-Content-Type-Options"] = "nosniff"
        headers["Cache-Control"] = "no-store"
        if (mime == "text/html") {
            headers["Content-Security-Policy"] = buildCsp()
        }

        val rangeHeader = request.requestHeaders["Range"]
        if (rangeHeader != null) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader)
            val total = f.length()
            if (m != null && total > 0) {
                val start = m.groupValues[1].toLongOrNull() ?: 0L
                val end = if (m.groupValues[2].isEmpty()) total - 1
                else (m.groupValues[2].toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
                if (start > end || start >= total) {
                    return WebResourceResponse(
                        mime, encoding, 416, "Range Not Satisfiable",
                        mapOf("Content-Range" to "bytes */$total"),
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                headers["Content-Range"] = "bytes $start-$end/$total"
                headers["Accept-Ranges"] = "bytes"
                val len = end - start + 1
                val stream: InputStream = LimitedInputStream(FileInputStream(f), start, len)
                return WebResourceResponse(mime, encoding, 206, "Partial Content", headers, stream)
            }
        }

        return try {
            WebResourceResponse(mime, encoding, 200, "OK", headers, FileInputStream(f))
        } catch (e: Exception) {
            blockResponse(404)
        }
    }

    private fun buildCsp(): String {
        val p = plan ?: return "default-src 'self'"
        return if (!networkAllowed) {
            "default-src 'self' data: blob:; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; font-src 'self' data:; " +
                "media-src 'self' data: blob:; connect-src 'self'; " +
                "object-src 'none'; frame-ancestors 'none'"
        } else {
            val extra = p.manifest.allowedHosts.joinToString(" ") { "https://$it" }
            "default-src 'self' data: blob:; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob: $extra; font-src 'self' data: $extra; " +
                "media-src 'self' data: blob: $extra; connect-src 'self' $extra; " +
                "object-src 'none'; frame-ancestors 'none'"
        }
    }

    private fun blockResponse(code: Int): WebResourceResponse = WebResourceResponse(
        "text/plain", "UTF-8", code,
        if (code == 403) "Forbidden" else "Not Found",
        mapOf("X-Content-Type-Options" to "nosniff"),
        java.io.ByteArrayInputStream(ByteArray(0))
    )

    /** Serves ZIP entries directly (experimental, off by default — spec D8). */
    inner class ZipHandler(private val archive: File) {
        private val zip: ZipFile? = try { ZipFile(archive) } catch (e: Exception) { null }

        fun serve(url: Uri): WebResourceResponse? {
            val zf = zip ?: return null
            val rel = url.path?.removePrefix("/") ?: return null
            val norm = PathSanitizer.sanitizeRelativePath(rel) ?: return null
            return try {
                val entry = zf.getEntry(norm) ?: return blockResponse(404)
                if (entry.isDirectory) return blockResponse(404)
                val mime = MimeTypes.fromPath(norm)
                val encoding = if (MimeTypes.isText(mime)) "UTF-8" else null
                WebResourceResponse(
                    mime, encoding, 200, "OK",
                    mapOf(
                        "X-Content-Type-Options" to "nosniff",
                        "Cache-Control" to "no-store"
                    ),
                    zf.getInputStream(entry)
                )
            } catch (e: Exception) {
                blockResponse(404)
            }
        }

        fun close() {
            try { zip?.close() } catch (e: Exception) {}
        }
    }

    private class LimitedInputStream(
        input: InputStream,
        skip: Long,
        private var remaining: Long
    ) : FilterInputStream(input) {
        init {
            var toSkip = skip
            while (toSkip > 0) {
                val n = input.skip(toSkip)
                if (n <= 0) break
                toSkip -= n
            }
        }

        override fun read(): Int {
            if (remaining <= 0) return -1
            val r = super.read()
            if (r >= 0) remaining--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    // ------------------------------------------------------------- page state

    fun onPageStarted() {
        pageFinishedSeen = false
        startLoadWatchdog()
        binding.loadingView.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE
        binding.pageProgress.visibility = View.VISIBLE
        binding.pageProgress.progress = 0
    }

    fun onPageFinished() {
        pageFinishedSeen = true
        cancelLoadWatchdog()
        binding.loadingView.visibility = View.GONE
        binding.pageProgress.visibility = View.GONE
        if (!paintChecked) {
            uiHandler.removeCallbacks(paintCheckRunnable)
            uiHandler.postDelayed(paintCheckRunnable, PAINT_CHECK_DELAY_MS)
        }
    }

    fun onProgress(progress: Int) {
        binding.pageProgress.progress = progress
        binding.pageProgress.visibility = if (progress < 100) View.VISIBLE else View.GONE
    }

    fun onLoadError(message: String, url: String? = null) {
        val p = plan
        val online = p != null && !p.manifest.isOffline
        val detail = buildString {
            append(message)
            if (online) append("\n\n").append(getString(R.string.runtime_err_network_hint))
        }
        showError(getString(R.string.runtime_err_generic, detail), retryable = true, url = url)
    }

    private fun showError(message: String, retryable: Boolean, url: String? = null) {
        cancelLoadWatchdog()
        binding.loadingView.visibility = View.GONE
        binding.pageProgress.visibility = View.GONE
        binding.handoffView.visibility = View.GONE
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        if (url.isNullOrBlank()) {
            binding.errorUrl.visibility = View.GONE
            openBrowserUrl = null
        } else {
            binding.errorUrl.visibility = View.VISIBLE
            binding.errorUrl.text = getString(R.string.runtime_err_url_line, url)
            openBrowserUrl = url
        }
        binding.btnOpenBrowser.visibility =
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) View.VISIBLE else View.GONE
        binding.btnRetry.visibility = if (retryable) View.VISIBLE else View.GONE
        addConsoleLine("ERR", message)
        // Errors must always be diagnosable: open the console alongside the message.
        if (binding.consoleOverlay.visibility != View.VISIBLE) {
            binding.consoleText.text = consoleBuffer.joinToString("\n")
            binding.consoleOverlay.visibility = View.VISIBLE
        }
    }

    fun onRendererGone() {
        val old = webView
        webView = null
        if (old != null) {
            old.stopLoading()
            (old.parent as? ViewGroup)?.removeView(old)
            old.destroy()
        }
        addConsoleLine("SYS", "renderer crashed — recreated WebView")
        setupWebViewAndLoad()
    }

    private fun retryLoad() {
        binding.errorView.visibility = View.GONE
        binding.btnRetry.visibility = View.VISIBLE
        val p = plan ?: return
        if (app.settings.current.previewEngine == "browser") {
            if (!p.manifest.isOffline && !networkAllowed) {
                when (p.networkPolicy) {
                    NetworkPolicy.DENY -> showNetworkDeniedDialog(p)
                    else -> showNetworkAskDialog(p)
                }
            } else if (p.manifest.isOffline && session == null) {
                // Nothing extracted yet (an earlier step failed) — redo the full flow.
                proceedLoad()
            } else {
                launchInBrowser()
            }
            return
        }
        if (!p.manifest.isOffline && !networkAllowed) {
            when (p.networkPolicy) {
                NetworkPolicy.DENY -> showNetworkDeniedDialog(p)
                else -> showNetworkAskDialog(p)
            }
        } else {
            val wv = webView
            if (wv != null) wv.reload() else proceedLoad()
        }
    }

    private fun confirmExitDialog() {
        val name = plan?.manifest?.name ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.runtime_exit_title, name))
            .setMessage(R.string.runtime_exit_msg)
            .setPositiveButton(R.string.action_close) { _, _ -> finish() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .also { activeDialog = it }
    }

    // ------------------------------------------------------------- custom view

    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        binding.btnDiag.visibility = View.GONE
        binding.customViewContainer.removeAllViews()
        binding.customViewContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.customViewContainer.visibility = View.VISIBLE
        applyFullscreen(true)
    }

    fun onHideCustomView() {
        binding.customViewContainer.removeAllViews()
        binding.customViewContainer.visibility = View.GONE
        binding.btnDiag.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        applyFullscreen(plan?.fullscreen == true)
    }

    // ------------------------------------------------------------- chooser/download

    /**
     * WebView permission prompt (camera / microphone), gated by the v1.5.0 per-app
     * App settings: a resource is granted only when the matching toggle is enabled
     * AND the Android runtime permission is granted (requested on the fly).
     */
    fun onWebPermissionRequest(request: android.webkit.PermissionRequest) {
        val wapId = plan?.wapId
        val prefs = if (wapId != null) app.appPrefs.get(wapId) else null
        if (prefs == null) {
            request.deny()
            return
        }
        val wanted = HashSet<String>(request.resources.toList())
        val runtimePerms = ArrayList<String>()
        if (wanted.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) && prefs.camera) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // granted below
            } else {
                runtimePerms.add(android.Manifest.permission.CAMERA)
            }
        }
        if (wanted.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE) && prefs.mic) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // granted below
            } else {
                runtimePerms.add(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        if (runtimePerms.isNotEmpty()) {
            pendingWebPermission = request
            try {
                webPermissionLauncher.launch(runtimePerms.toTypedArray())
            } catch (e: Exception) {
                pendingWebPermission = null
                request.deny()
            }
            return
        }
        val grant = ArrayList<String>()
        if (wanted.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) && prefs.camera &&
            checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) grant.add(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        if (wanted.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE) && prefs.mic &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) grant.add(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (grant.isEmpty()) {
            addConsoleLine("SEC", "permission denied: ${request.resources.joinToString(",")}")
            request.deny()
        } else {
            request.grant(grant.toTypedArray())
        }
    }

    fun onFileChooser(
        callback: android.webkit.ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        if (fileChooserCallback != null) {
            callback.onReceiveValue(null)
            return true
        }
        fileChooserCallback = callback
        return try {
            fileChooserLauncher.launch(params.createIntent())
            true
        } catch (e: ActivityNotFoundException) {
            fileChooserCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    private fun onDownloadRequested(url: String) {
        if (!networkAllowed) {
            Toast.makeText(this, R.string.runtime_err_network_denied, Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("https://")) {
            Toast.makeText(this, R.string.runtime_download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        pendingDownloadUrl = url
        val name = Uri.parse(url).lastPathSegment ?: "download.bin"
        downloadLauncher.launch(name)
    }

    private fun startDownload(url: String, dest: Uri) {
        Ui.runIO {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { ins ->
                    contentResolver.openOutputStream(dest, "wt")?.use { out ->
                        ins.copyTo(out, 65536)
                    }
                }
                Ui.onMain {
                    Toast.makeText(this, R.string.runtime_download_ready, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Ui.onMain {
                    Toast.makeText(
                        this,
                        getString(R.string.runtime_download_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ------------------------------------------------------------- console

    fun addConsoleLine(level: String, message: String) {
        consoleBuffer.addLast("[$level] $message")
        if (consoleBuffer.size > 200) consoleBuffer.removeFirst()
        if (binding.consoleOverlay.visibility == View.VISIBLE) {
            binding.consoleText.text = consoleBuffer.joinToString("\n")
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        cancelLoadWatchdog()
        uiHandler.removeCallbacks(paintCheckRunnable)
        stopPreviewServer()
        bridgeHost?.shutdown()
        bridgeHost = null
        features?.shutdown()
        features = null
        val wv = webView
        webView = null
        if (wv != null) {
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (!isChangingConfigurations) {
            // OnExit: delete extracted files, keep archives/config/app data (spec §10.2).
            session?.let { app.sessionManager.endSession(it.sessionId) }
            session = null
        }
        zipHandler?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PREVIEW_PROJECT = "WAP_PREVIEW_PROJECT"
        /** Give slow-but-progressing loads time; extended while progress moves. */
        private const val LOAD_WATCHDOG_MS = 15_000L
        /** Grace period after onPageFinished before the first-paint frame check. */
        private const val PAINT_CHECK_DELAY_MS = 1_500L
    }
}

/** Small helper kept separate to avoid importing ZipFile in the main flow. */
private object ManifestParserHelper {
    fun fromFile(file: File, maxQuota: Long): com.iamtungingit.wam.wap.manifest.ManifestParser.Result =
        com.iamtungingit.wam.wap.manifest.ManifestParser.fromArchive(file, maxQuota)
}
