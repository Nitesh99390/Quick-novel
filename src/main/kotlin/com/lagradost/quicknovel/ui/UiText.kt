package com.lagradost.quicknovel.ui

sealed class UiImage {
    data class Image(
        val url: String,
        val headers: Map<String, String>? = null,
        val errorDrawable: Int? = null
    ) : UiImage()

    data class Drawable(val resId: Int) : UiImage()
    data class Uri(val uri: android.net.Uri) : UiImage()
}

fun img(url: String?, headers: Map<String, String>? = null, errorDrawable: Int? = null): UiImage? {
    if (url.isNullOrBlank()) return null
    return UiImage.Image(url, headers, errorDrawable)
}

fun img(drawable: Int): UiImage = UiImage.Drawable(drawable)
