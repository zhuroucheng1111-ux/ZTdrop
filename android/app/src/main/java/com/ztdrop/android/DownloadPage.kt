package com.ztdrop.android

import java.util.Locale

internal fun formatFileSize(size: Long): String {
    if (size == 0L) return "0 KB"
    if (size < 1024L) return "<1 KB"
    val (unit, label) = when {
        size >= 1024L * 1024 * 1024 -> Pair(1024L * 1024 * 1024, "GB")
        size >= 1024L * 1024 -> Pair(1024L * 1024, "MB")
        else -> Pair(1024L, "KB")
    }
    val value = String.format(Locale.US, "%.1f", size.toDouble() / unit).removeSuffix(".0")
    return "$value $label"
}

private fun escapeHtml(text: String): String = text.replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

internal fun renderDownloadPage(template: String, name: String, size: Long, token: String): String = template
    .replace("__TOKEN__", token)
    .replace("__FILE_SIZE__", escapeHtml(formatFileSize(size)))
    .replace("__STATUS_SCRIPT__", "")
    .replace("__FILE_NAME__", escapeHtml(name))
