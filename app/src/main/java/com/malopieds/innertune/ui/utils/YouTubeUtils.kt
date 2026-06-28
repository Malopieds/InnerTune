package com.malopieds.innertune.ui.utils

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this
    // lh3.googleusercontent.com URLs: replace the =w{W}-h{H} size segment
    val lh3Regex = "=w(\\d+)-h(\\d+)".toRegex()
    lh3Regex.find(this)?.let { match ->
        val w = width ?: height!!
        val h = height ?: width!!
        return replaceRange(match.range, "=w$w-h$h-p-l90-rj")
    }
    // yt3.ggpht.com URLs: replace existing =s{N} size parameter
    val ggphtRegex = "=s(\\d+)".toRegex()
    ggphtRegex.find(this)?.let { match ->
        val s = width ?: height!!
        return replaceRange(match.range, "=s$s")
    }
    return this
}
