/*
 * Copyright (c) 2025 Auxio Project
 * SizeUtil.kt is part of Auxio.
 */

package org.oxycblt.auxio.util

import java.util.Locale

/**
 * Format a byte count into a human readable string (B, KB, MB, GB, ...).
 */
fun Long.formatFileSize(): String {
    if (this <= 0L) return "0 B"
    var size = this.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var unitIndex = 0
    while (size >= 1024.0 && unitIndex < units.size - 1) {
        size /= 1024.0
        unitIndex++
    }
    val formatted = when {
        size >= 100 -> String.format(Locale.getDefault(), "%.0f", size)
        size >= 10 -> String.format(Locale.getDefault(), "%.1f", size)
        else -> String.format(Locale.getDefault(), "%.2f", size)
    }
    return "$formatted ${units[unitIndex]}"
}
