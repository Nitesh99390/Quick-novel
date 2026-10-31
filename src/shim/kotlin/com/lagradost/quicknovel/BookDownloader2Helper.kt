package com.lagradost.quicknovel

import java.io.File

/** Subset of the upstream BookDownloader2Helper used by MainAPI.kt (same logic). */
object BookDownloader2Helper {
    const val IMPORT_SOURCE = "Download"
    const val IMPORT_SOURCE_PDF = "importsourcepdf"
    private val fs = File.separatorChar
    private const val reservedChars = "|\\?*<\":>+[]/'"

    fun sanitizeFilename(name: String): String {
        var tempName = name
        for (c in reservedChars) {
            tempName = tempName.replace(c, ' ')
        }
        return tempName.replace("  ", " ").trim()
    }

    fun getDirectory(apiName: String, author: String, name: String): String =
        "${fs}Epub${fs}${apiName}${fs}${author}${fs}${name}".replace("$fs$fs", "$fs")

    fun getFilenameIMG(apiName: String, author: String, name: String): String =
        "${getDirectory(apiName, author, name)}${fs}poster.jpg".replace("$fs$fs", "$fs")
}
