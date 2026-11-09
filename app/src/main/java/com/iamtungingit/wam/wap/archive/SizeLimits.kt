package com.iamtungingit.wam.wap.archive

/** Hard limits enforced on actual streamed bytes (spec §7.1, §9.6). */
object SizeLimits {
    const val MAX_ARCHIVE_BYTES: Long = 50L * 1024 * 1024
    const val MAX_ENTRIES = 5000
    const val MAX_ENTRY_BYTES: Long = 50L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED: Long = 200L * 1024 * 1024
    const val MAX_RATIO = 100L
    const val RATIO_FLOOR_BYTES: Long = 4096L

    const val MAX_ICON_BYTES = 1024 * 1024
    const val MAX_ICON_DIMENSION = 2048
    // Widget icons are cached at up to 256px so they stay crisp on the adaptive-icon
    // widget canvas at high densities (xxhdpi content ≈ 216px); still far below the
    // RemoteViews bitmap budget (spec §3.7).
    const val MAX_WIDGET_ICON_PX = 256

    const val MAX_EDITABLE_FILE = 1024 * 1024

    const val BRIDGE_MAX_FILE = 1024 * 1024
    const val BRIDGE_MAX_FILES = 256
    const val BRIDGE_MAX_MESSAGE = 256 * 1024
    const val BRIDGE_RATE_PER_SECOND = 200
}
