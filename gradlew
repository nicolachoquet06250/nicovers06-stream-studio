#!/usr/bin/env sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION="9.5.0"
DIST_DIR="$APP_HOME/.gradle-dist"
GRADLE_HOME="$DIST_DIR/gradle-$GRADLE_VERSION"
ARCHIVE="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    mkdir -p "$DIST_DIR"
    TEMP_ARCHIVE="$ARCHIVE.part"
    trap 'rm -f "$TEMP_ARCHIVE"' EXIT INT TERM

    echo "Téléchargement de Gradle $GRADLE_VERSION..." >&2
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 --output "$TEMP_ARCHIVE" "$URL"
    elif command -v wget >/dev/null 2>&1; then
        wget --tries=3 --output-document="$TEMP_ARCHIVE" "$URL"
    else
        echo "Erreur : curl ou wget est requis pour télécharger Gradle." >&2
        exit 1
    fi

    mv "$TEMP_ARCHIVE" "$ARCHIVE"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q "$ARCHIVE" -d "$DIST_DIR"
    else
        echo "Erreur : unzip est requis pour extraire Gradle." >&2
        exit 1
    fi
    rm -f "$ARCHIVE"
    trap - EXIT INT TERM
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
