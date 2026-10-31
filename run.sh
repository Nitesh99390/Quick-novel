#!/usr/bin/env bash
# Builds (if needed) and starts the bot. Settings are read from .env
set -e
cd "$(dirname "$0")"
if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env - put your BOT_TOKEN in it and run ./run.sh again"; exit 1
fi
[ -f build/libs/quicknovel-bot.jar ] || ./build.sh fatJar
exec java ${JAVA_OPTS:--Xmx384m -XX:+UseSerialGC} -Djava.awt.headless=true -jar build/libs/quicknovel-bot.jar "$@"
