package com.lagradost.quicknovel

import java.io.File

/** There is no Android activity on the server. Kept so upstream code compiles. */
object CommonActivity {
    class ServerContext(val filesDir: File, val cacheDir: File)

    val activity: ServerContext? = null
}
