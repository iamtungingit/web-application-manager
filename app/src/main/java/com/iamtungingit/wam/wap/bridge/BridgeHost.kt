package com.iamtungingit.wam.wap.bridge

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.iamtungingit.wam.wap.manifest.WapManifest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Restricted per-WAP JavaScript bridge (spec D11, §9.6).
 * Uses WebViewCompat.addWebMessageListener + addDocumentStartJavaScript restricted to the
 * WAP's own synthetic origin. Never addJavascriptInterface. If the WebView on the device
 * does not support these features the bridge stays disabled (localStorage still works).
 */
class BridgeHost(
    private val webView: WebView,
    private val domain: String,
    private val wapId: String,
    private val manifest: WapManifest,
    private val dataStore: WapDataStore,
    private val networkAllowed: Boolean,
    private val ttsAllowed: Boolean,
    private val onTtsBlocked: () -> Unit,
    /**
     * Official JS API surface (v1.6.0): camera / voice recorder / net.send /
     * permissions. Null when the runtime could not create it — the API methods
     * then answer with a clear error instead of hanging.
     */
    private val features: WapFeatures? = null,
    private val onExit: () -> Unit
) {

    private val rate = BridgeProtocol.RateLimiter(com.iamtungingit.wam.wap.archive.SizeLimits.BRIDGE_RATE_PER_SECOND)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Text-to-speech (v1.5.0 per-app setting). Created lazily on first speak()
    // when the app has the "Text to speech" permission; torn down with the session.
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    val supported: Boolean
        get() = manifest.effectiveBridge &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun attach() {
        if (!supported) return
        val origin = "https://$domain"
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, SHIM, setOf(origin))
            WebViewCompat.addWebMessageListener(webView, BridgeProtocol.BRIDGE_NAME, setOf(origin),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        // Defense in depth: reject messages not from the WAP's own origin.
                        if (sourceOrigin.toString() != origin) return
                        handleMessage(message.data ?: return, replyProxy)
                    }
                })
        } catch (e: Exception) {
            // Bridge unavailable on this WebView build; WAP must degrade gracefully.
        }
    }

    private fun handleMessage(text: String, reply: JavaScriptReplyProxy) {
        if (!manifest.effectiveBridge) return
        if (text.length > BridgeProtocol.MAX_MESSAGE) return
        if (!rate.allow()) {
            reply.postMessage(BridgeProtocol.errJson(-1L, "rate_limited"))
            return
        }
        val req = BridgeProtocol.parse(text) ?: return

        fun ok(result: Any?) = reply.postMessage(BridgeProtocol.okJson(req.id, result))
        fun err(e: String) = reply.postMessage(BridgeProtocol.errJson(req.id, e))

        when (req.method) {
            "info" -> ok(
                JSONObject()
                    .put("id", wapId)
                    .put("version", manifest.version)
                    .put("runtimeVersion", WapManifest.RUNTIME_VERSION)
                    .put("network", if (networkAllowed) "allowed" else "denied")
            )
            "data.list" -> ok(JSONArray(dataStore.list()))
            "data.readText" -> {
                val name = BridgeProtocol.stringArg(req.args, "name")
                if (name == null) err("invalid_name")
                else {
                    val content = dataStore.readText(name)
                    if (content == null) err("not_found") else ok(content)
                }
            }
            "data.writeText" -> {
                val name = BridgeProtocol.stringArg(req.args, "name")
                val value = BridgeProtocol.stringArg(req.args, "text")
                if (name == null || value == null) err("invalid_args")
                else {
                    val r = dataStore.writeText(name, value)
                    if (r.ok) ok(true) else err(r.error ?: "write_failed")
                }
            }
            "data.delete" -> {
                val name = BridgeProtocol.stringArg(req.args, "name")
                if (name == null) err("invalid_name") else ok(dataStore.delete(name))
            }
            "data.usage" -> {
                val (bytes, files) = dataStore.usage()
                ok(JSONObject().put("bytes", bytes).put("files", files).put("quota", manifest.quotaBytes))
            }
            "exit" -> {
                ok(true)
                mainHandler.post { onExit() }
            }
            "tts.speak" -> {
                // Per-app permission gate (v1.5.0): denied apps get a clear error
                // instead of silence.
                if (!ttsAllowed) {
                    onTtsBlocked()
                    err("tts_disabled")
                } else {
                    val text = BridgeProtocol.stringArg(req.args, "text")
                    if (text.isNullOrEmpty()) err("invalid_args")
                    else if (text.length > 4000) err("too_long")
                    else {
                        speak(text)
                        ok(true)
                    }
                }
            }
            "tts.stop" -> {
                if (!ttsAllowed) {
                    onTtsBlocked()
                    err("tts_disabled")
                } else {
                    stopSpeech()
                    ok(true)
                }
            }
            // ------------------------------------------------ official JS API (v1.6.0)
            "permissions.get" -> ok(features?.permissionsJson() ?: JSONObject())
            "camera.capture" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else f.cameraCapture { result -> mainHandler.post { safeReply(reply, result) } }
            }
            "mic.record" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else f.micRecord(req.args) { result -> mainHandler.post { safeReply(reply, result) } }
            }
            "mic.stop" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else {
                    f.stopRecording()
                    ok(true)
                }
            }
            "net.send" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else f.netSend(req.args) { result -> mainHandler.post { safeReply(reply, result) } }
            }
            // --------------------------------------- official JS API (v1.7.0 additions)
            "device.vibrate" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else f.deviceVibrate(req.args.optInt("ms", 200)) { result ->
                    mainHandler.post { safeReply(reply, result) }
                }
            }
            "device.info" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else f.deviceInfo { result -> mainHandler.post { safeReply(reply, result) } }
            }
            "clipboard.read" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else f.clipboardRead { result -> mainHandler.post { safeReply(reply, result) } }
            }
            "clipboard.write" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else {
                    val text = BridgeProtocol.stringArg(req.args, "text")
                    if (text == null) err("invalid_args")
                    else f.clipboardWrite(text) { result ->
                        mainHandler.post { safeReply(reply, result) }
                    }
                }
            }
            "notify" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else f.notify(
                    BridgeProtocol.stringArg(req.args, "title") ?: "",
                    BridgeProtocol.stringArg(req.args, "body") ?: ""
                ) { result -> mainHandler.post { safeReply(reply, result) } }
            }
            "share.text" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else {
                    val text = BridgeProtocol.stringArg(req.args, "text")
                    if (text == null) err("invalid_args")
                    else f.shareText(text) { result -> mainHandler.post { safeReply(reply, result) } }
                }
            }
            "share.file" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else {
                    val name = BridgeProtocol.stringArg(req.args, "name")
                    if (name == null) err("invalid_name")
                    else f.shareFile(name) { result -> mainHandler.post { safeReply(reply, result) } }
                }
            }
            "storage.export" -> {
                val f = features
                if (f == null) err("bridge_features_unavailable")
                else {
                    val name = BridgeProtocol.stringArg(req.args, "name")
                    if (name == null) err("invalid_name")
                    else f.exportFile(name) { result -> mainHandler.post { safeReply(reply, result) } }
                }
            }
            "data.readDataUrl" -> {
                // Reads a binary saved by camera/mic as a data: URL (≤ 1 MB).
                val name = BridgeProtocol.stringArg(req.args, "name")
                if (name == null) err("invalid_name")
                else {
                    val bytes = dataStore.readBinary(name)
                    if (bytes == null) err("not_found")
                    else ok(
                        "data:${com.iamtungingit.wam.util.MimeTypes.fromPath(name)};base64," +
                            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    )
                }
            }
            else -> err("unknown_method")
        }
    }

    /** reply.postMessage on a WebView that was just destroyed can throw — swallow it. */
    private fun safeReply(reply: androidx.webkit.JavaScriptReplyProxy, payload: JSONObject) {
        try {
            reply.postMessage(payload.toString())
        } catch (e: Exception) {
            // Session is tearing down; the caller is gone.
        }
    }

    private fun speak(text: String) {
        mainHandler.post {
            if (tts == null) {
                tts = TextToSpeech(webView.context) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                }
            }
            // utteranceId distinguishes the init-flushed call; QUEUE_FLUSH replaces
            // anything currently spoken (web speechSynthesis semantics).
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wap-$wapId-${System.nanoTime()}")
        }
    }

    private fun stopSpeech() {
        mainHandler.post { tts?.stop() }
    }

    /** Releases the TTS engine with the session (RuntimeActivity teardown). */
    fun shutdown() {
        mainHandler.post {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {}
            tts = null
            ttsReady = false
        }
    }

    companion object {
        /** Promise-based ES5 shim injected at document start (window.WAP). */
        const val SHIM: String = """(function(){
if (window.WAP) { return; }
var seq = 0;
var pending = {};
function deliver(text) {
  try {
    var m = JSON.parse(text);
    var p = pending[m.id];
    if (!p) { return; }
    delete pending[m.id];
    if (m.ok) { p.res(m.result); } else { p.rej(new Error(m.error || 'WAP error')); }
  } catch (e) {}
}
function handler(a, b) {
  var data = null;
  if (typeof a === 'string') { data = a; }
  else if (a && typeof a === 'object' && a.data !== undefined) { data = a.data; }
  else if (typeof b === 'string') { data = b; }
  if (data != null) { deliver(data); }
}
try { WAPBridge.addEventListener('message', handler); } catch (e) {}
try { WAPBridge.onmessage = handler; } catch (e) {}
function send(method, args) {
  return new Promise(function(resolve, reject) {
    var id = ++seq;
    pending[id] = { res: resolve, rej: reject };
    WAPBridge.postMessage(JSON.stringify({ id: id, method: method, args: args || {} }));
  });
}
function dataReadText(name) { return send('data.readText', { name: name }); }
function dataWriteText(name, text) { return send('data.writeText', { name: name, text: String(text) }); }
window.WAP = {
  info: function() { return send('info'); },
  exit: function() { return send('exit'); },
  data: {
    list: function() { return send('data.list'); },
    readText: dataReadText,
    writeText: dataWriteText,
    delete: function(name) { return send('data.delete', { name: name }); },
    usage: function() { return send('data.usage'); },
    readJson: function(name) {
      return dataReadText(name).then(function(t) { return t ? JSON.parse(t) : null; });
    },
    writeJson: function(name, obj) { return dataWriteText(name, JSON.stringify(obj)); }
  },
  tts: {
    speak: function(text) { return send('tts.speak', { text: String(text) }); },
    stop: function() { return send('tts.stop'); }
  },
  camera: {
    capture: function() { return send('camera.capture', {}); }
  },
  mic: {
    record: function(seconds) { return send('mic.record', { seconds: seconds || 10 }); },
    stop: function() { return send('mic.stop'); }
  },
  net: {
    send: function(url, opts) {
      opts = opts || {};
      return send('net.send', {
        url: String(url),
        method: opts.method || 'POST',
        headers: opts.headers || {},
        body: opts.body === undefined ? '' : String(opts.body),
        base64: !!opts.base64
      });
    }
  },
  device: {
    vibrate: function(ms) { return send('device.vibrate', { ms: ms || 200 }); },
    info: function() { return send('device.info', {}); }
  },
  clipboard: {
    read: function() { return send('clipboard.read', {}); },
    write: function(text) { return send('clipboard.write', { text: String(text) }); }
  },
  notify: function(title, body) {
    return send('notify', { title: String(title || ''), body: String(body || '') });
  },
  share: {
    text: function(text) { return send('share.text', { text: String(text) }); },
    file: function(name) { return send('share.file', { name: String(name) }); }
  },
  storage: {
    export: function(name) { return send('storage.export', { name: String(name) }); }
  },
  permissions: {
    get: function() { return send('permissions.get', {}); }
  }
};
})();"""
    }
}
