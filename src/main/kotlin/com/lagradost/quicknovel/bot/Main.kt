package com.lagradost.quicknovel.bot

import com.lagradost.quicknovel.bot.generated.UpstreamProviders

fun main() {
    println(UpstreamProviders.enabled().joinToString { it.name })
}
