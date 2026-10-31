package android.util

import com.lagradost.quicknovel.bot.Logger

/** android.util.Log routed to the bot logger */
object Log {
    @JvmStatic fun v(tag: String?, msg: String): Int { Logger.debug("[$tag] $msg"); return 0 }
    @JvmStatic fun d(tag: String?, msg: String): Int { Logger.debug("[$tag] $msg"); return 0 }
    @JvmStatic fun i(tag: String?, msg: String): Int { Logger.info("[$tag] $msg"); return 0 }
    @JvmStatic fun w(tag: String?, msg: String): Int { Logger.warn("[$tag] $msg"); return 0 }
    @JvmStatic fun e(tag: String?, msg: String): Int { Logger.error("[$tag] $msg"); return 0 }
    @JvmStatic fun e(tag: String?, msg: String, tr: Throwable?): Int { Logger.error("[$tag] $msg", tr); return 0 }
}
