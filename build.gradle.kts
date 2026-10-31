import java.io.File

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "com.lagradost.quicknovel.bot"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    // Same libraries (and versions) the QuickNovel app uses for its providers
    implementation("com.github.Blatzar:NiceHttp:0.4.18")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") // DO NOT CHANGE (same as upstream)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // org.json is built into Android, on the JVM we need it explicitly (WattpadProvider)
    implementation("org.json:json:20251224")
    // jsoup 1.22 is annotated with JSpecify nullness annotations
    implementation("org.jspecify:jspecify:1.0.0")
    // WebP covers (most novel sites) -> JPEG for e-readers & Telegram thumbnails
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")

    testImplementation(kotlin("test"))
}

// Use whatever JDK runs Gradle (17+), compile to Java 17 bytecode
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/* ------------------------------------------------------------------------------------------
 * Upstream QuickNovel integration
 *
 * The QuickNovel repository is a git submodule in upstream/QuickNovel. Instead of copying
 * (and forking) its code, we compile the original provider sources directly on the JVM.
 * The small Android surface they touch is provided by the shims in src/main/kotlin.
 * ------------------------------------------------------------------------------------------ */
val upstreamMain = layout.projectDirectory.dir("upstream/QuickNovel/app/src/main")
val upstreamPkg = upstreamMain.dir("java/com/lagradost/quicknovel")
val upstreamRes = upstreamMain.dir("res")
val generatedSrc = layout.buildDirectory.dir("generated/upstream/kotlin")

/** Providers that can not be compiled on the JVM can be excluded with -PexcludeProviders=A.kt,B.kt */
val excludedProviders: List<String> =
    (findProperty("excludeProviders") as String?)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: emptyList()

val syncUpstream by tasks.registering(Sync::class) {
    description = "Copies the unmodified QuickNovel provider sources into the build"
    group = "upstream"
    from(upstreamPkg) {
        include(
            "providers/**",
            "extractors/**",
            "MainAPI.kt",
            "tools.kt",
            "util/ParCollections.kt",
            "util/DefaultImagesHeaders.kt",
        )
        excludedProviders.forEach { exclude("providers/$it") }
    }
    into(generatedSrc.map { it.dir("com/lagradost/quicknovel") })
    // keep the generated R + registry files that live in another folder
}

val generatedGlue = layout.buildDirectory.dir("generated/upstream-glue/kotlin")

val generateUpstreamGlue by tasks.registering {
    description = "Generates R.kt (resource ids) and the provider registry from upstream Apis.kt"
    group = "upstream"
    val resDir = upstreamRes.asFile
    val apisFile = upstreamPkg.file("util/Apis.kt").asFile
    val providersDir = upstreamPkg.dir("providers").asFile
    val outDir = generatedGlue
    val excluded = excludedProviders
    inputs.dir(resDir)
    inputs.file(apisFile)
    inputs.dir(providersDir)
    inputs.property("excluded", excluded)
    outputs.dir(outDir)

    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()

        // ---------------- R.kt ----------------
        fun valuesNames(file: File, tag: String): List<Pair<String, String>> {
            if (!file.exists()) return emptyList()
            val regex = Regex("<$tag\\s+name=\"([A-Za-z0-9_]+)\"[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            return regex.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }

        val values = File(resDir, "values")
        val colors = valuesNames(File(values, "colors.xml"), "color").map { it.first }.toMutableSet()
        File(resDir, "color").listFiles()?.forEach { colors += it.nameWithoutExtension }
        val strings = valuesNames(File(values, "strings.xml"), "string")
        val drawables = sortedSetOf<String>()
        resDir.listFiles()?.filter { it.isDirectory && (it.name.startsWith("drawable") || it.name.startsWith("mipmap")) }
            ?.forEach { dir -> dir.listFiles()?.forEach { drawables += it.name.substringBefore('.') } }

        var id = 0x7f000001
        fun kotlinString(s: String): String {
            val unescaped = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            return "\"" + unescaped.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("$", "\\$") + "\""
        }

        val r = StringBuilder()
        r.appendLine("// GENERATED FROM upstream/QuickNovel/app/src/main/res - DO NOT EDIT")
        r.appendLine("package com.lagradost.quicknovel\n")
        r.appendLine("@Suppress(\"unused\", \"ClassName\")")
        r.appendLine("object R {")
        val drawableIds = mutableListOf<Pair<Int, String>>()
        r.appendLine("    object color {")
        colors.sorted().forEach { r.appendLine("        const val $it: Int = ${id++}") }
        r.appendLine("    }")
        r.appendLine("    object drawable {")
        drawables.forEach { drawableIds += id to it; r.appendLine("        const val $it: Int = ${id++}") }
        r.appendLine("    }")
        val stringIds = mutableListOf<Pair<Int, String>>()
        r.appendLine("    object string {")
        strings.map { it.first }.distinct().forEach { stringIds += id to it; r.appendLine("        const val $it: Int = ${id++}") }
        r.appendLine("    }")
        r.appendLine("}\n")
        val stringValues = strings.toMap()
        r.appendLine("/** English string values of the upstream app (R.string id -> text) */")
        r.appendLine("object RStrings {")
        r.appendLine("    val values: Map<Int, String> = hashMapOf(")
        stringIds.forEach { (sid, name) -> r.appendLine("        $sid to ${kotlinString(stringValues[name] ?: name)},") }
        r.appendLine("    )")
        r.appendLine("    fun get(id: Int): String? = values[id]")
        r.appendLine("}\n")
        r.appendLine("/** Drawable names (R.drawable id -> resource name) */")
        r.appendLine("object RDrawables {")
        r.appendLine("    val names: Map<Int, String> = hashMapOf(")
        drawableIds.forEach { (did, name) -> r.appendLine("        $did to \"$name\",") }
        r.appendLine("    )")
        r.appendLine("}")
        File(out, "com/lagradost/quicknovel").apply { mkdirs() }.resolve("R.kt").writeText(r.toString())

        // ---------------- Provider registry (mirrors util/Apis.kt) ----------------
        val apisText = apisFile.readText()
        val block = Regex("val apis: List<MainAPI> = arrayOf\\((.*?)\\)\\.sortedBy", RegexOption.DOT_MATCHES_ALL)
            .find(apisText)?.groupValues?.get(1) ?: error("Could not find the provider list in Apis.kt")
        val existingClasses = providersDir.listFiles()!!
            .filter { f -> f.extension == "kt" && excluded.none { it == f.name } }
            .flatMap { f ->
                // ignore classes that are commented out upstream (/* ... */ and // lines)
                val code = f.readText()
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                    .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
                Regex("class\\s+([A-Za-z0-9_]+)").findAll(code).map { it.groupValues[1] }.toList()
            }
            .toSet()
        val enabled = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        block.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val commented = line.startsWith("//")
            val code = if (commented) line.removePrefix("//").trim() else line.substringBefore("//")
            val name = Regex("^([A-Z][A-Za-z0-9_]*)\\(\\)").find(code.trim())?.groupValues?.get(1) ?: return@forEach
            if (name !in existingClasses) return@forEach
            if (commented) disabled += name else enabled += name
        }
        // Reddit is handled specially by the app (getApiFromNameOrNull)
        val special = listOf("RedditProvider").filter { it in existingClasses }

        val p = StringBuilder()
        p.appendLine("// GENERATED FROM upstream/QuickNovel .../util/Apis.kt - DO NOT EDIT")
        p.appendLine("package com.lagradost.quicknovel.bot.generated\n")
        p.appendLine("import com.lagradost.quicknovel.MainAPI")
        (enabled + disabled + special).distinct().forEach { p.appendLine("import com.lagradost.quicknovel.providers.$it") }
        p.appendLine()
        p.appendLine("object UpstreamProviders {")
        p.appendLine("    /** Providers enabled in the QuickNovel app (same order/filter as Apis.apis) */")
        p.appendLine("    fun enabled(): List<MainAPI> = listOf<MainAPI>(")
        enabled.forEach { p.appendLine("        $it(),") }
        p.appendLine("    ).sortedBy { it.name }\n")
        p.appendLine("    /** Providers that exist upstream but are commented out in the app (dead/broken sites) */")
        p.appendLine("    fun disabled(): List<MainAPI> = listOf<MainAPI>(")
        disabled.forEach { p.appendLine("        $it(),") }
        p.appendLine("    ).sortedBy { it.name }\n")
        p.appendLine("    /** Providers only reachable by URL in the app */")
        p.appendLine("    fun special(): List<MainAPI> = listOf<MainAPI>(")
        special.forEach { p.appendLine("        $it(),") }
        p.appendLine("    )")
        p.appendLine("}")
        File(out, "com/lagradost/quicknovel/bot/generated").apply { mkdirs() }.resolve("UpstreamProviders.kt")
            .writeText(p.toString())
        println("QuickNovel: ${enabled.size} enabled, ${disabled.size} disabled, ${special.size} special providers")
    }
}

sourceSets {
    main {
        kotlin.srcDir(syncUpstream.map { generatedSrc.get() })
        kotlin.srcDir(generateUpstreamGlue.map { generatedGlue.get() })
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(syncUpstream, generateUpstreamGlue)
    compilerOptions {
        // upstream code produces many harmless warnings, keep the output readable
        suppressWarnings.set(true)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjvm-default=all", "-opt-in=kotlin.RequiresOptIn")
    }
}

application {
    mainClass.set("com.lagradost.quicknovel.bot.MainKt")
    applicationName = "quicknovel-bot"
}

/** Single runnable jar: java -jar build/libs/quicknovel-bot.jar */
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds a runnable fat jar"
    archiveFileName.set("quicknovel-bot.jar")
    manifest { attributes["Main-Class"] = "com.lagradost.quicknovel.bot.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    }
}

tasks.named("build") { dependsOn(fatJar) }

tasks.test { useJUnitPlatform() }
