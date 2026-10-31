# ---------- build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
COPY . .
# fetch the upstream QuickNovel sources if the submodule was not checked out
RUN if [ ! -f upstream/QuickNovel/app/src/main/java/com/lagradost/quicknovel/MainAPI.kt ]; then \
      rm -rf upstream/QuickNovel && git clone --depth 1 https://github.com/LagradOst/QuickNovel.git upstream/QuickNovel; \
    fi
RUN sed -i 's/-Xmx600m/-Xmx1500m/' gradle.properties && ./build.sh fatJar

# ---------- run ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/quicknovel-bot.jar /app/quicknovel-bot.jar
ENV DATA_DIR=/app/data JAVA_OPTS="-Xmx384m -XX:+UseSerialGC -Djava.awt.headless=true"
VOLUME ["/app/data"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quicknovel-bot.jar \"$@\"", "--"]
