package com.iamtungingit.wam.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.RemoteViews
import com.iamtungingit.wam.BuildConfig
import com.iamtungingit.wam.R
import com.iamtungingit.wam.ui.main.MainActivity
import com.iamtungingit.wam.ui.runtime.RuntimeActivity
import com.iamtungingit.wam.ui.widget.WidgetConfigureActivity
import com.iamtungingit.wam.wap.install.Registry
import com.iamtungingit.wam.wap.runtime.IntentOverrideResolver
import kotlin.math.roundToInt

/**
 * Builds RemoteViews for widgets (spec §11). Widgets never run HTML — they are a
 * launcher surface only; backgrounds are pre-scaled bitmaps (spec §3.7).
 *
 * Problem 1 (user): "make the widgets hold 1 app space and stop with that tall
 * crap" — a widget is now exactly ONE app icon: the label/subtitle stack under the
 * icon is gone (it made every widget look like a tall blob instead of an app
 * shortcut) and the widget is fixed at 1×1 (wap_widget_info resizeMode=none).
 *
 * Sizing (user issue: "fix widgets based on DPI"): the launcher reports the
 * widget's cell in **dp** (OPTION_APPWIDGET_*_WIDTH/HEIGHT). We convert those dp
 * to **pixels with the device's real density** (mdpi 1.0×, hdpi 1.5×, xhdpi 2.0×,
 * xxhdpi 3.0×, xxxhdpi 4.0× — and in-between buckets like the Redmi 14C's 280dpi
 * too), and draw the background bitmap at the FULL cell size, so the widget fills
 * its cell edge-to-edge like an adaptive launcher icon does. The icon content uses
 * the 72/108 adaptive "safe zone" of the shorter cell side.
 */
object WidgetRenderer {

    /** Adaptive-icon full-bleed canvas in dp. */
    private const val ADAPTIVE_ICON_DP = 108
    /** Adaptive-icon safe zone (visible content) relative to the canvas: 72/108. */
    private const val SAFE_ZONE_RATIO = 72f / 108f

    fun providerComponent(context: Context): ComponentName =
        ComponentName(context, WapWidgetProvider::class.java)

    fun renderFor(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val store = WidgetStore(context)
        val record = store.get(appWidgetId)
        val rv = RemoteViews(context.packageName, R.layout.widget_wap)
        val registry = Registry(context)

        when {
            record == null -> {
                // Missing record (spec §11.4): "Tap to set up" → opens configuration.
                renderIconState(
                    context, rv, manager, appWidgetId,
                    icon = IconCache.defaultIconBitmap(context, 96),
                    shape = "rounded",
                    bg = context.getColor(R.color.widget_default_bg),
                    pendingIntent = configurePendingIntent(context, appWidgetId),
                    statusLabel = context.getString(R.string.widget_setup_hint)
                )
            }
            registry.get(record.wapId) == null -> {
                // Missing/uninstalled WAP (spec §3.9): plain icon → opens WAM.
                renderIconState(
                    context, rv, manager, appWidgetId,
                    icon = IconCache.defaultIconBitmap(context, 96, tint = 0xB3FFFFFF.toInt()),
                    shape = record.shape,
                    bg = record.backgroundColor ?: context.getColor(R.color.widget_default_bg),
                    pendingIntent = PendingIntent.getActivity(
                        context, appWidgetId,
                        Intent(context, MainActivity::class.java)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    ),
                    statusLabel = context.getString(R.string.widget_app_removed)
                )
            }
            else -> renderNormal(context, rv, manager, appWidgetId, record, registry)
        }

        try {
            manager.updateAppWidget(appWidgetId, rv)
        } catch (e: Exception) {
            // Widget host may be unavailable briefly; ignore this update.
        }
    }

    /**
     * Cell size in PIXELS for this widget on this screen. The launcher reports the
     * cell in dp; multiplying by the device's real density makes the rendered
     * bitmap exactly the size the launcher will display — identical proportions on
     * mdpi, hdpi, xhdpi, xxhdpi and xxxhdpi phones.
     */
    private data class CellPx(val width: Int, val height: Int, val density: Float, val dpi: Int)

    private fun cellPxFor(context: Context, manager: AppWidgetManager, appWidgetId: Int): CellPx {
        val opts = manager.getAppWidgetOptions(appWidgetId)
        val minWdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, ADAPTIVE_ICON_DP)
        val minHdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, ADAPTIVE_ICON_DP)
        val dm = context.resources.displayMetrics
        val w = (minWdp * dm.density).roundToInt().coerceIn(32, 2048)
        val h = (minHdp * dm.density).roundToInt().coerceIn(32, 2048)
        return CellPx(w, h, dm.density, dm.densityDpi)
    }

    /** Human-readable density bucket for the debug log (what the user calls "mdpi / xxxhdpi"). */
    private fun densityBucket(dpi: Int): String = when {
        dpi <= 120 -> "ldpi"
        dpi <= 160 -> "mdpi"
        dpi <= 213 -> "tvdpi"
        dpi <= 240 -> "hdpi"
        dpi <= 280 -> "hdpi(280)"
        dpi <= 320 -> "xhdpi"
        dpi <= 420 -> "xhdpi(400)"
        dpi <= 480 -> "xxhdpi"
        else -> "xxxhdpi"
    }

    /** One app icon: full-cell background + the icon inside the adaptive safe zone.
     *  [statusLabel] ("Tap to set up" / "App removed") is drawn into the background
     *  bitmap and the icon shrinks a little — normal widgets stay label-free. */
    private fun renderIconState(
        context: Context,
        rv: RemoteViews,
        manager: AppWidgetManager,
        appWidgetId: Int,
        icon: Bitmap?,
        shape: String,
        bg: Int,
        pendingIntent: PendingIntent,
        statusLabel: String? = null
    ) {
        rv.setInt(R.id.widget_root, "setBackgroundColor", 0x00000000)
        val cell = cellPxFor(context, manager, appWidgetId)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "WAM.Widget",
                "render #$appWidgetId cell=${cell.width}x${cell.height}px " +
                    "density=${cell.density} (${densityBucket(cell.dpi)})"
            )
        }
        // Background drawn at the FULL cell size — the fitXY ImageView in the layout
        // scales it 1:1, so the widget fills the cell edge-to-edge on every density.
        rv.setImageViewBitmap(
            R.id.widget_bg,
            IconCache.widgetBackground(shape, bg, cell.width, cell.height, statusLabel)
        )

        // Icon content: adaptive safe zone of the SHORTER cell side (icon look);
        // slightly smaller when a status label shares the cell.
        val zone = if (statusLabel != null) SAFE_ZONE_RATIO * 0.66f else SAFE_ZONE_RATIO
        val contentPx = (minOf(cell.width, cell.height) * zone).roundToInt().coerceAtLeast(24)
        val scaled = icon?.let { fitSquare(it, contentPx) }
        if (scaled != null) {
            rv.setImageViewBitmap(R.id.widget_icon, scaled)
            rv.setViewVisibility(R.id.widget_icon, android.view.View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.widget_icon, android.view.View.GONE)
        }
        rv.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
    }

    private fun renderNormal(
        context: Context,
        rv: RemoteViews,
        manager: AppWidgetManager,
        appWidgetId: Int,
        record: WidgetRecord,
        @Suppress("UNUSED_PARAMETER") registry: Registry
    ) {
        val bg = record.backgroundColor ?: context.getColor(R.color.widget_default_bg)
        val icon = IconCache.loadBitmap(context, record.wapId)
            ?: IconCache.defaultIconBitmap(context, 96)
        renderIconState(
            context, rv, manager, appWidgetId,
            icon = icon,
            shape = record.shape,
            bg = bg,
            pendingIntent = launchPendingIntent(context, record)
        )
    }

    /** Scales a bitmap to fit inside a target square (never up-scaled beyond 2x). */
    private fun fitSquare(src: Bitmap, target: Int): Bitmap {
        if (src.width <= 0 || src.height <= 0) return src
        val scale = minOf(target.toFloat() / src.width, target.toFloat() / src.height)
        val tw = (src.width * scale).roundToInt().coerceAtLeast(1)
        val th = (src.height * scale).roundToInt().coerceAtLeast(1)
        return if (tw == src.width && th == src.height) src
        else Bitmap.createScaledBitmap(src, tw, th, true)
    }

    /**
     * Unique data URI + request code per widget so PendingIntents never collide
     * (extras alone do not define PendingIntent identity — spec §3.8).
     */
    fun launchPendingIntent(context: Context, record: WidgetRecord): PendingIntent {
        val intent = Intent(context, RuntimeActivity::class.java)
            .setData(Uri.parse("wap://run/${record.wapId}?widget=${record.widgetId}"))
            .putExtra(IntentOverrideResolver.EXTRA_ID, record.wapId)
            .putExtra(IntentOverrideResolver.EXTRA_WIDGET_ID, record.widgetId)
        if (record.entry != null) intent.putExtra(IntentOverrideResolver.EXTRA_ENTRY, record.entry)
        if (record.fullscreen != null) intent.putExtra(IntentOverrideResolver.EXTRA_FULLSCREEN, record.fullscreen)
        if (record.theme != null) intent.putExtra(IntentOverrideResolver.EXTRA_THEME, record.theme)
        if (record.network != "inherit") intent.putExtra(IntentOverrideResolver.EXTRA_ALLOW_NETWORK, record.network)
        return PendingIntent.getActivity(
            context,
            record.widgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun configurePendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetConfigureActivity::class.java)
            .setData(Uri.parse("wap://configure/$appWidgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getActivity(
            context,
            100000 + appWidgetId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Re-renders every bound widget of a WAP (used after install/update/uninstall). */
    fun updateAllForWap(context: Context, wapId: String) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(providerComponent(context)) ?: return
        val store = WidgetStore(context)
        for (id in ids) {
            val rec = store.get(id)
            if (rec == null || rec.wapId == wapId) renderFor(context, manager, id)
        }
    }

    /** Re-renders all widgets of this provider (reboot / update / restore). */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(providerComponent(context)) ?: return
        for (id in ids) renderFor(context, manager, id)
    }

    /** "Create widget" via direct pinning (spec D4, §11.4b). */
    fun requestPin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        if (!manager.isRequestPinAppWidgetSupported) return false
        return try {
            manager.requestPinAppWidget(providerComponent(context), null, null)
        } catch (e: Exception) {
            false
        }
    }
}
