package com.lagradost.quicknovel

import java.io.File

/** Subset of upstream BookDownloader2Helper used by MainAPI.kt (same logic as upstream) */
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
        return tempName.replace(Regex("\\s+"), " ").trim()
    }

    fun getDirectory(apiName: String, author: String, name: String): String =
        "${fs}$apiName${fs}$author${fs}$name".replace("$fs$fs", "$fs")

    fun getFilenameIMG(apiName: String, author: String, name: String): String =
        "${getDirectory(apiName, author, name)}${fs}poster.jpg".replace("$fs$fs", "$fs")
}
