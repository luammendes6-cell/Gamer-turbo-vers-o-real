#!/usr/bin/env sh
# Pequeno lançador do Gradle 8.9 para manter o projeto independente de GitHub.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.9
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
DIST_ROOT="$GRADLE_USER_HOME/gamer-turbo-gradle"
GRADLE_HOME="$DIST_ROOT/gradle-$GRADLE_VERSION"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    mkdir -p "$DIST_ROOT"
    ARCHIVE="$DIST_ROOT/gradle-$GRADLE_VERSION-bin.zip"
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    echo "Baixando Gradle $GRADLE_VERSION do servidor oficial..."
    if command -v curl >/dev/null 2>&1; then
        curl -fL "$URL" -o "$ARCHIVE"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$ARCHIVE" "$URL"
    else
        echo "Instale curl ou wget para baixar o Gradle." >&2
        exit 1
    fi
    if ! command -v unzip >/dev/null 2>&1; then
        echo "Instale unzip para extrair o Gradle." >&2
        exit 1
    fi
    unzip -q -o "$ARCHIVE" -d "$DIST_ROOT"
fi
exec "$GRADLE_HOME/bin/gradle" -p "$APP_HOME" "$@"
