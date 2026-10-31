package com.lagradost.quicknovel.ui

/** Data-only port of the upstream UiImage (no ImageView helpers on the server). */
sealed class UiImage {
    data class Image(
        val url: String,
        val headers: Map<String, String>? = null,
        val errorDrawable: Int? = null
    ) : UiImage()

    data class Drawable(val resId: Int) : UiImage()
    data class Uri(val uri: android.net.Uri) : UiImage()
}

@JvmName("imgNull")
fun img(
    url: String?,
    headers: Map<String, String>? = null,
    errorDrawable: Int? = null
): UiImage? {
    if (url.isNullOrBlank()) return null
    return UiImage.Image(url, headers, errorDrawable)
}

fun img(
    url: String,
    headers: Map<String, String>? = null,
    errorDrawable: Int? = null
): UiImage = UiImage.Image(url, headers, errorDrawable)

fun img(drawable: Int): UiImage = UiImage.Drawable(drawable)
