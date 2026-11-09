package com.iamtungingit.wam.wap.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.iamtungingit.wam.R
import com.iamtungingit.wam.settings.AppPrefsRepository
import com.iamtungingit.wam.wap.archive.SizeLimits
import com.iamtungingit.wam.wap.manifest.WapManifest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The native side of WAM's OFFICIAL JavaScript API (v1.6.0 user request #3:
 * "Make official JS API to interact with app in settings like record/camera/send
 * data"). Every call is gated by the per-app permissions the user set in
 * Apps → long-press → App settings (all OFF by default):
 *
 *  - [cameraCapture]  WAP.camera.capture()  → takes a photo via the system camera
 *    app (ACTION_IMAGE_CAPTURE — the camera app itself holds the camera, so no
 *    extra prompt beyond the per-app toggle), downscales it, saves it into the
 *    app's data store and (offline apps) the session's /.wam/ folder.
 *  - [micRecord]      WAP.mic.record()      → records microphone audio (AAC in an
 *    .m4a) with the RECORD_AUDIO runtime permission, bounded to 30 s.
 *  - [netSend]        WAP.net.send()        → performs an HTTP POST/PUT/PATCH to an
 *    https URL (the DANGEROUS "requests to send data" permission — off = blocked).
 *  - [permissionsJson] WAP.permissions.get() → what this app may do right now.
 *
 * Results are delivered EXACTLY once per call via the onResult callback.
 */
class WapFeatures(
    private val activity: AppCompatActivity,
    private val wapId: String,
    @Suppress("unused") private val manifest: WapManifest,
    private val offline: Boolean,
    private val sessionDir: File?,
    private val dataStore: WapDataStore?,
    private val prefs: () -> AppPrefsRepository.AppPrefs,
    private val networkAllowed: () -> Boolean,
    private val cameraLauncher: ActivityResultLauncher<Uri>,
    private val micPermissionLauncher: ActivityResultLauncher<String>,
    /** POST_NOTIFICATIONS prompt (Android 13+) for WAP.notify(). */
    private val notifyPermissionLauncher: ActivityResultLauncher<String>,
    /** Save-as dialog (ACTION_CREATE_DOCUMENT) for WAP.storage.export(). */
    private val exportLauncher: ActivityResultLauncher<String>,
    private val log: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val netExecutor = Executors.newSingleThreadExecutor()

    // ---- camera state
    private var cameraPending: ((JSONObject) -> Unit)? = null
    private var photoFile: File? = null

    // ---- microphone state
    private class MicPending(val seconds: Int, val onResult: (JSONObject) -> Unit)
    private var micPending: MicPending? = null
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recCallback: ((JSONObject) -> Unit)? = null
    private val autoStop = Runnable { stopRecording() }

    // ---- v1.7.0 capability state
    private var pendingNotify: ((JSONObject) -> Unit)? = null
    private var pendingExportName: String? = null
    private var pendingExportCallback: ((JSONObject) -> Unit)? = null

    // ---------------------------------------------------------------- errors

    private fun err(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)

    private fun fileBaseName(prefix: String, ext: String): String =
        "$prefix-${System.currentTimeMillis()}.$ext"

    // ---------------------------------------------------------------- camera

    /** WAP.camera.capture() — system camera app → downscaled JPEG. */
    fun cameraCapture(onResult: (JSONObject) -> Unit) {
        if (!prefs().camera) {
            log("camera.capture blocked — Camera permission is off in App settings")
            onResult(err("camera_disabled"))
            return
        }
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            onResult(err("no_camera"))
            return
        }
        if (cameraPending != null) {
            onResult(err("capture_in_progress"))
            return
        }
        val dir = File(activity.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        val uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", f)
        } catch (e: Exception) {
            onResult(err("capture_setup_failed"))
            return
        }
        photoFile = f
        cameraPending = onResult
        try {
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            cameraPending = null
            photoFile = null
            f.delete()
            onResult(err("capture_launch_failed"))
        }
    }

    /** Called from RuntimeActivity's TakePicture launcher. */
    fun onCameraResult(success: Boolean) {
        val cb = cameraPending ?: return
        cameraPending = null
        val f = photoFile
        photoFile = null
        if (!success || f == null || !f.isFile) {
            f?.delete()
            cb(err("capture_cancelled"))
            return
        }
        try {
            val bytes = downscaleJpeg(f, MAX_PHOTO_DIMENSION)
            f.delete()
            if (bytes == null) {
                cb(err("capture_failed"))
                return
            }
            cb(saveCapture("cam", "jpg", bytes))
        } catch (e: Exception) {
            f.delete()
            cb(err("capture_failed"))
        }
    }

    /** Downscale to ≤[maxDim] px and re-encode as JPEG (keeps bridge payloads sane). */
    private fun downscaleJpeg(src: File, maxDim: Int): ByteArray? {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(src.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        var w = opts.outWidth
        var h = opts.outHeight
        while (w / 2 >= maxDim || h / 2 >= maxDim) { sample *= 2; w /= 2; h /= 2 }
        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath, decodeOpts) ?: return null
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) {
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1), true
            )
            if (scaled !== bmp) bmp.recycle()
            scaled
        } else bmp
        val bos = java.io.ByteArrayOutputStream()
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
        out.recycle()
        return bos.toByteArray()
    }

    /** Saves bytes into the data store + (offline) the session /.wam/ folder. */
    private fun saveCapture(prefix: String, ext: String, bytes: ByteArray): JSONObject {
        val name = fileBaseName(prefix, ext)
        val result = JSONObject().put("name", name).put("bytes", bytes.size).put("ok", true)
        dataStore?.writeBinary(name, bytes)
        if (offline && sessionDir != null) {
            try {
                val dir = File(sessionDir, WAM_DIR).apply { mkdirs() }
                File(dir, name).writeBytes(bytes)
                result.put("url", "/$WAM_DIR/$name")
            } catch (e: Exception) {
                // Session copy is a convenience for offline <img>/<audio>; the data
                // store copy above is the durable one.
            }
        }
        return result
    }

    // ---------------------------------------------------------------- microphone

    /** WAP.mic.record({ seconds }) — AAC .m4a, bounded to [MAX_RECORD_SECONDS]. */
    fun micRecord(args: JSONObject, onResult: (JSONObject) -> Unit) {
        if (!prefs().mic) {
            log("mic.record blocked — Voice recorder permission is off in App settings")
            onResult(err("mic_disabled"))
            return
        }
        val seconds = args.optInt("seconds", DEFAULT_RECORD_SECONDS)
            .coerceIn(1, MAX_RECORD_SECONDS)
        if (recorder != null || micPending != null) {
            onResult(err("recording_in_progress"))
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            micPending = MicPending(seconds, onResult)
            try {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } catch (e: Exception) {
                micPending = null
                onResult(err("mic_permission_denied"))
            }
            return
        }
        startRecording(seconds, onResult)
    }

    /** Called from RuntimeActivity's RequestPermission launcher. */
    fun onMicPermissionResult(granted: Boolean) {
        val p = micPending ?: return
        micPending = null
        if (!granted) {
            log("mic.record denied — RECORD_AUDIO not granted")
            p.onResult(err("mic_permission_denied"))
            return
        }
        startRecording(p.seconds, p.onResult)
    }

    private fun hasPermission(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun startRecording(seconds: Int, onResult: (JSONObject) -> Unit) {
        if (recorder != null) {
            onResult(err("recording_in_progress"))
            return
        }
        val dir = File(activity.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "rec-${System.currentTimeMillis()}.m4a")
        val r = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(activity)
            else MediaRecorder()
        } catch (e: Exception) {
            onResult(err("record_failed"))
            return
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.setMaxDuration(seconds * 1000)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    mainHandler.post { stopRecording() }
                }
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try { r.release() } catch (ignored: Exception) {}
            f.delete()
            onResult(err("record_failed"))
            return
        }
        recorder = r
        recFile = f
        recCallback = onResult
        // Hard stop even if MaxDuration never fires (some OEM recorders are sloppy).
        mainHandler.removeCallbacks(autoStop)
        mainHandler.postDelayed(autoStop, seconds * 1000L + 500)
    }

    /** Finishes the running recording and delivers the saved file. */
    fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        val cb = recCallback
        recCallback = null
        val f = recFile
        recFile = null
        mainHandler.removeCallbacks(autoStop)
        try {
            r.stop()
        } catch (e: Exception) {
            try { r.release() } catch (ignored: Exception) {}
            f?.delete()
            cb?.invoke(err("record_too_short"))
            return
        }
        try { r.release() } catch (ignored: Exception) {}
        if (cb == null || f == null || !f.isFile) {
            f?.delete()
            cb?.invoke(err("record_failed"))
            return
        }
        try {
            val bytes = f.readBytes()
            f.delete()
            if (bytes.size > SizeLimits.BRIDGE_MAX_FILE) {
                cb(err("file_too_large"))
                return
            }
            cb(saveCapture("rec", "m4a", bytes))
        } catch (e: Exception) {
            f.delete()
            cb(err("record_failed"))
        }
    }

    // ---------------------------------------------------------------- net.send

    /** WAP.net.send(url, {method, headers, body, base64}) — the dangerous one. */
    fun netSend(args: JSONObject, onResult: (JSONObject) -> Unit) {
        if (!prefs().sendData) {
            log("net.send blocked — 'Requests to send data' is off in App settings")
            onResult(err("send_data_disabled"))
            return
        }
        if (!networkAllowed()) {
            onResult(err("network_denied"))
            return
        }
        val urlStr = args.optString("url", "")
        val method = args.optString("method", "POST").uppercase()
        if (method !in ALLOWED_METHODS) {
            onResult(err("method_not_allowed"))
            return
        }
        val url = try {
            URL(urlStr)
        } catch (e: Exception) {
            onResult(err("invalid_url"))
            return
        }
        val https = url.protocol.equals("https", ignoreCase = true)
        val loopbackHttp = url.protocol.equals("http", ignoreCase = true) &&
            (url.host == "localhost" || url.host == "127.0.0.1")
        if (!https && !loopbackHttp) {
            // Sending personal data over plain http would leak it in transit.
            onResult(err("insecure_url"))
            return
        }
        val body: ByteArray = try {
            val raw = args.optString("body", "")
            val decoded = if (args.optBoolean("base64")) {
                android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            } else {
                raw.toByteArray(Charsets.UTF_8)
            }
            if (decoded.size > MAX_SEND_BODY) {
                onResult(err("body_too_large"))
                return
            }
            decoded
        } catch (e: Exception) {
            onResult(err("invalid_body"))
            return
        }
        val headers = args.optJSONObject("headers") ?: JSONObject()

        netExecutor.execute {
            onResult(doNetSend(url, method, headers, body))
        }
    }

    private fun doNetSend(url: URL, method: String, headers: JSONObject, body: ByteArray): JSONObject {
        var conn: HttpURLConnection? = null
        return try {
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                for (key in headers.keys()) {
                    val lower = key.lowercase()
                    // The platform owns these; everything else is passed through
                    // (Authorization is allowed — the user granted "send data").
                    if (lower in FORBIDDEN_HEADERS) continue
                    try { setRequestProperty(key, headers.optString(key, "")) } catch (e: Exception) {}
                }
            }
            conn = c
            c.getOutputStream().use { out -> out.write(body) }
            val status = c.responseCode
            val contentType = c.getHeaderField("Content-Type") ?: "text/plain"
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val respBytes = readCapped(stream, MAX_RESPONSE_BYTES)
            val isText = contentType.substringBefore(';').trim().lowercase().let { t ->
                t.startsWith("text/") || t.contains("json") || t.contains("xml") || t.contains("urlencoded")
            }
            return JSONObject()
                .put("ok", status in 200..299)
                .put("status", status)
                .put("contentType", contentType)
                .put(
                    "body",
                    if (isText) String(respBytes, Charsets.UTF_8)
                    else android.util.Base64.encodeToString(respBytes, android.util.Base64.NO_WRAP)
                )
                .put("base64", !isText)
        } catch (e: Exception) {
            err("request_failed")
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /** Reads at most [cap] bytes (never more — responses are truncated at the cap). */
    private fun readCapped(stream: java.io.InputStream?, cap: Int): ByteArray {
        if (stream == null) return ByteArray(0)
        return try {
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            var total = 0
            stream.use { ins ->
                while (total < cap) {
                    val n = ins.read(buf, 0, minOf(buf.size, cap - total))
                    if (n < 0) break
                    bos.write(buf, 0, n)
                    total += n
                }
            }
            bos.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    // ---------------------------------------------------------------- permissions

    /** WAP.permissions.get() — what this app may do right now. */
    fun permissionsJson(): JSONObject = JSONObject()
        .put("camera", prefs().camera)
        .put("mic", prefs().mic)
        .put("tts", prefs().tts)
        .put("sendData", prefs().sendData)
        .put("vibrate", prefs().vibrate)
        .put("clipboard", prefs().clipboard)
        .put("notifications", prefs().notifications)
        .put("deviceInfo", prefs().deviceInfo)
        .put("share", prefs().share)
        .put("exportFiles", prefs().exportFiles)
        .put("network", if (networkAllowed()) "allowed" else "denied")

    // ------------------------------------------------- v1.7.0: more API permissions

    /** WAP.device.vibrate(ms) — short haptic feedback, 10–5000 ms. */
    fun deviceVibrate(ms: Int, onResult: (JSONObject) -> Unit) {
        if (!prefs().vibrate) {
            log("device.vibrate blocked — Vibrate permission is off in App settings")
            onResult(err("vibrate_disabled"))
            return
        }
        mainHandler.post {
            try {
                val duration = ms.coerceIn(10, MAX_VIBRATE_MS).toLong()
                val vibrateOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val mgr = activity.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                        as? android.os.VibratorManager
                    mgr?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    activity.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                }?.let { v ->
                    if (v.hasVibrator()) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(
                            duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        true
                    } else false
                } ?: false
                onResult(if (vibrateOk) JSONObject().put("ok", true).put("ms", duration)
                         else err("no_vibrator"))
            } catch (e: Exception) {
                onResult(err("vibrate_failed"))
            }
        }
    }

    /** WAP.device.info() — model / OS / screen / language / battery. */
    fun deviceInfo(onResult: (JSONObject) -> Unit) {
        if (!prefs().deviceInfo) {
            log("device.info blocked — Device info permission is off in App settings")
            onResult(err("device_info_disabled"))
            return
        }
        mainHandler.post {
            try {
                val dm = activity.resources.displayMetrics
                val battery = batteryPercent()
                val info = JSONObject()
                    .put("model", Build.MODEL)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("androidVersion", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("screenWidth", dm.widthPixels)
                    .put("screenHeight", dm.heightPixels)
                    .put("density", dm.density)
                    .put("language", java.util.Locale.getDefault().toString())
                if (battery != null) info.put("batteryPercent", battery)
                info.put("ok", true)
                onResult(info)
            } catch (e: Exception) {
                onResult(err("device_info_failed"))
            }
        }
    }

    /** Sticky battery broadcast — no permission needed, returns null if absent. */
    private fun batteryPercent(): Int? {
        return try {
            val intent = activity.registerReceiver(null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0) (level * 100) / scale.coerceAtLeast(1) else null
        } catch (e: Exception) {
            null
        }
    }

    /** WAP.clipboard.write(text) — copies to the system clipboard. */
    fun clipboardWrite(text: String, onResult: (JSONObject) -> Unit) {
        if (!prefs().clipboard) {
            log("clipboard.write blocked — Clipboard permission is off in App settings")
            onResult(err("clipboard_disabled"))
            return
        }
        mainHandler.post {
            try {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                if (cm == null) {
                    onResult(err("clipboard_failed"))
                    return@post
                }
                cm.setPrimaryClip(android.content.ClipData.newPlainText("WAP", text.take(MAX_CLIPBOARD)))
                onResult(JSONObject().put("ok", true))
            } catch (e: Exception) {
                onResult(err("clipboard_failed"))
            }
        }
    }

    /** WAP.clipboard.read() — DANGEROUS: may read anything the user last copied. */
    fun clipboardRead(onResult: (JSONObject) -> Unit) {
        if (!prefs().clipboard) {
            log("clipboard.read blocked — Clipboard permission is off in App settings")
            onResult(err("clipboard_disabled"))
            return
        }
        mainHandler.post {
            try {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                val text = cm?.primaryClip?.itemCount?.let { count ->
                    (0 until count).firstNotNullOfOrNull { cm.primaryClip?.getItemAt(it)?.text }
                }
                onResult(JSONObject().put("ok", true)
                    .put("text", text?.toString()?.take(MAX_CLIPBOARD) ?: ""))
            } catch (e: Exception) {
                onResult(err("clipboard_failed"))
            }
        }
    }

    /** WAP.notify(title, body) — a normal notification on the WAM apps channel. */
    fun notify(title: String, body: String, onResult: (JSONObject) -> Unit) {
        if (!prefs().notifications) {
            log("notify blocked — Notifications permission is off in App settings")
            onResult(err("notifications_disabled"))
            return
        }
        // Android 13+: POST_NOTIFICATIONS must be granted at runtime first.
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)) {
            pendingNotify = onResult
            try {
                notifyPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
                pendingNotify = null
                onResult(err("notify_permission_denied"))
            }
            return
        }
        postNotification(title, body, onResult)
    }

    /** Called from RuntimeActivity's notification-permission launcher. */
    fun onNotifyPermissionResult(granted: Boolean) {
        val cb = pendingNotify ?: return
        pendingNotify = null
        if (!granted) {
            log("notify denied — POST_NOTIFICATIONS not granted")
            cb(err("notify_permission_denied"))
            return
        }
        postNotification(null, null, cb)
    }

    private fun postNotification(title: String?, body: String?, onResult: (JSONObject) -> Unit) {
        mainHandler.post {
            try {
                val nm = activity.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                    as? android.app.NotificationManager
                if (nm == null || !nm.areNotificationsEnabled()) {
                    onResult(err("notify_permission_denied"))
                    return@post
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    val channel = android.app.NotificationChannel(
                        NOTIFICATION_CHANNEL,
                        activity.getString(R.string.wap_notification_channel),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT
                    )
                    channel.description = activity.getString(R.string.wap_notification_channel_desc)
                    nm.createNotificationChannel(channel)
                }
                val b = androidx.core.app.NotificationCompat.Builder(activity, NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_globe)
                    .setContentTitle((title ?: activity.getString(R.string.app_name)).take(120))
                    .setContentText((body ?: "").take(400))
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText((body ?: "").take(2000)))
                    .setAutoCancel(true)
                nm.notify(wapId.hashCode(), b.build())
                onResult(JSONObject().put("ok", true))
            } catch (e: Exception) {
                onResult(err("notify_failed"))
            }
        }
    }

    /** WAP.share.text(text) — the system share sheet, user picks the target. */
    fun shareText(text: String, onResult: (JSONObject) -> Unit) {
        if (!prefs().share) {
            log("share.text blocked — Share permission is off in App settings")
            onResult(err("share_disabled"))
            return
        }
        mainHandler.post {
            try {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text.take(MAX_SHARE_TEXT))
                activity.startActivity(Intent.createChooser(send,
                    activity.getString(R.string.action_share)))
                onResult(JSONObject().put("ok", true))
            } catch (e: Exception) {
                onResult(err("share_failed"))
            }
        }
    }

    /** WAP.share.file(name) — shares a saved data file via the share sheet. */
    fun shareFile(name: String, onResult: (JSONObject) -> Unit) {
        if (!prefs().share) {
            log("share.file blocked — Share permission is off in App settings")
            onResult(err("share_disabled"))
            return
        }
        val bytes = dataStore?.readBinary(name)
        if (bytes == null) {
            onResult(err("not_found"))
            return
        }
        mainHandler.post {
            try {
                val dir = File(activity.cacheDir, "share").apply { mkdirs() }
                val safe = name.substringAfterLast('/')
                    .filter { it.isLetterOrDigit() || it in "._- " }.ifEmpty { "file" }
                val f = File(dir, safe)
                f.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", f)
                val send = Intent(Intent.ACTION_SEND)
                    .setType(com.iamtungingit.wam.util.MimeTypes.fromPath(safe))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(Intent.createChooser(send,
                    activity.getString(R.string.action_share)))
                onResult(JSONObject().put("ok", true))
            } catch (e: Exception) {
                onResult(err("share_failed"))
            }
        }
    }

    /** WAP.storage.export(name) — Save-as dialog; the user picks the destination. */
    fun exportFile(name: String, onResult: (JSONObject) -> Unit) {
        if (!prefs().exportFiles) {
            log("storage.export blocked — Export files permission is off in App settings")
            onResult(err("export_disabled"))
            return
        }
        val exists = dataStore?.list()?.contains(name) ?: false
        if (!exists) {
            onResult(err("not_found"))
            return
        }
        val safe = name.substringAfterLast('/').ifEmpty { "file" }
        pendingExportName = safe
        pendingExportCallback = onResult
        try {
            exportLauncher.launch(safe)
        } catch (e: Exception) {
            pendingExportName = null
            pendingExportCallback = null
            onResult(err("export_failed"))
        }
    }

    /** Called from RuntimeActivity's CreateDocument launcher. */
    fun onExportResult(uri: android.net.Uri?) {
        val cb = pendingExportCallback
        val name = pendingExportName
        pendingExportCallback = null
        pendingExportName = null
        if (cb == null) return
        if (uri == null || name == null) {
            cb(err("export_cancelled"))
            return
        }
        val bytes = dataStore?.readBinary(name)
        if (bytes == null) {
            cb(err("not_found"))
            return
        }
        // Copy off the main thread — files can be up to the data quota.
        netExecutor.execute {
            val ok = try {
                activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            } catch (e: Exception) {
                false
            }
            mainHandler.post {
                if (ok) cb(JSONObject().put("ok", true).put("name", name))
                else cb(err("export_failed"))
            }
        }
    }

    // ---------------------------------------------------------------- teardown

    /** Releases the recorder/temp files with the session (RuntimeActivity teardown). */
    fun shutdown() {
        mainHandler.removeCallbacks(autoStop)
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // stop() on an idle recorder throws — harmless, we only want it dead.
        }
        try {
            recorder?.release()
        } catch (e: Exception) {}
        recorder = null
        recCallback?.invoke(err("shutdown"))
        recCallback = null
        cameraPending?.invoke(err("shutdown"))
        cameraPending = null
        micPending?.onResult?.invoke(err("shutdown"))
        micPending = null
        pendingNotify?.invoke(err("shutdown"))
        pendingNotify = null
        pendingExportCallback?.invoke(err("shutdown"))
        pendingExportCallback = null
        pendingExportName = null
        photoFile?.delete()
        photoFile = null
        recFile?.delete()
        recFile = null
        netExecutor.shutdown()
    }

    companion object {
        /** Session-relative folder (offline apps) where captures are mirrored. */
        const val WAM_DIR = ".wam"
        const val MAX_PHOTO_DIMENSION = 1280
        const val DEFAULT_RECORD_SECONDS = 10
        const val MAX_RECORD_SECONDS = 30
        const val MAX_SEND_BODY = 2 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 512 * 1024
        // v1.7.0 capability limits.
        const val MAX_VIBRATE_MS = 5_000
        const val MAX_CLIPBOARD = 100_000
        const val MAX_SHARE_TEXT = 100_000
        const val NOTIFICATION_CHANNEL = "wap_apps"
        private val ALLOWED_METHODS = setOf("POST", "PUT", "PATCH")
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection")
    }
}
