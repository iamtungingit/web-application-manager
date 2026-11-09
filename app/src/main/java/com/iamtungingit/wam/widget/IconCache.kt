package com.iamtungingit.wam.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.iamtungingit.wam.R
import com.iamtungingit.wam.storage.Fs
import com.iamtungingit.wam.storage.Paths
import com.iamtungingit.wam.wap.archive.SafeExtractor
import com.iamtungingit.wam.wap.archive.SizeLimits

/**
 * Icon caching: archive icons are decoded with bounds checks and cached as ≤128px
 * PNGs without extracting the whole archive (spec §3.7, §7.2.7).
 */
object IconCache {

    /** Extracts the icon entry from the archive and caches a scaled PNG. */
    fun cacheFromArchive(context: Context, wapId: String, archive: java.io.File, iconPath: String?): Boolean {
        val dest = Paths.iconFileChecked(context, wapId) ?: return false
        if (iconPath == null) return false
        return try {
            val bytes = SafeExtractor.readEntry(archive, iconPath, SizeLimits.MAX_ICON_BYTES) ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            if (bounds.outWidth > SizeLimits.MAX_ICON_DIMENSION || bounds.outHeight > SizeLimits.MAX_ICON_DIMENSION) return false
            var sample = 1
            var w = bounds.outWidth
            while (w / 2 >= SizeLimits.MAX_WIDGET_ICON_PX) { sample *= 2; w /= 2 }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
            val scaled = if (bmp.width > SizeLimits.MAX_WIDGET_ICON_PX || bmp.height > SizeLimits.MAX_WIDGET_ICON_PX) {
                val scale = SizeLimits.MAX_WIDGET_ICON_PX.toFloat() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true
                )
            } else bmp
            java.io.FileOutputStream(dest).use { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            true
        } catch (e: Exception) {
            dest.delete()
            false
        }
    }

    fun loadBitmap(context: Context, wapId: String): Bitmap? {
        val f = Paths.iconFile(context, wapId)
        if (!f.isFile) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    /** Renders the built-in globe icon as a bitmap for widgets/lists. */
    fun defaultIconBitmap(context: Context, sizePx: Int = 96, tint: Int = 0xFFFFFFFF.toInt()): Bitmap? {
        return try {
            val d = ContextCompat.getDrawable(context, R.drawable.ic_globe) ?: return null
            d.setTint(tint)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(canvas)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Renders the widget background as an adaptive launcher-icon mask, drawn at the
     * exact cell size (width × height in pixels — computed from the launcher's dp
     * cell × the device's real density, see WidgetRenderer.cellPxFor). Shapes fill
     * the whole cell; the visible corner radius follows the adaptive-icon look
     * (≈ 1/4.5 of the SHORTER side), so a resized widget keeps icon-like corners.
     *
     * v1.7.0: the app-name label moved into the layout (widget_label TextView,
     * "make it look like an app") — this now draws only the shape/color tile.
     */
    fun widgetBackground(shape: String, color: Int, widthPx: Int, heightPx: Int): Bitmap {
        val w = widthPx.coerceIn(32, 2048)
        val h = heightPx.coerceIn(32, 2048)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        when (shape) {
            "circle" -> canvas.drawOval(rect, paint)
            "square" -> canvas.drawRect(rect, paint)
            else -> {
                val r = minOf(w, h) * 0.225f
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }
        return bmp
    }

    fun deleteIcon(context: Context, wapId: String) {
        Fs.deleteRec(Paths.iconFile(context, wapId))
    }
}
