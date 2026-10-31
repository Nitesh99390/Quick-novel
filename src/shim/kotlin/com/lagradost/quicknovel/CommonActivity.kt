package com.lagradost.quicknovel

import java.io.File

/** Minimal stand-in for an Android Activity – only what MainAPI touches. */
class ShimActivity(val filesDir: File)

/** There is no UI on the server, so there is never a foreground activity. */
object CommonActivity {
    var activity: ShimActivity? = null
}
