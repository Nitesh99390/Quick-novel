#!/usr/bin/env bash
# Builds build/libs/quicknovel-bot.jar inside a single JVM (works on 1 GB RAM machines).
set -e
cd "$(dirname "$0")"
if [ ! -f upstream/QuickNovel/app/src/main/java/com/lagradost/quicknovel/MainAPI.kt ]; then
  git submodule update --init --depth 1 upstream/QuickNovel
fi
# Same args as org.gradle.jvmargs => Gradle runs the build in this JVM instead of forking a daemon
export GRADLE_OPTS="$(grep '^org.gradle.jvmargs=' gradle.properties | cut -d= -f2-)"
exec ./gradlew --no-daemon --console=plain "${@:-fatJar}"
