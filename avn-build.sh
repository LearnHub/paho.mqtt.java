#!/usr/bin/env bash
# Builds the MQTT 5 client jar that the ClassVR Client ships
# (AVN repo: paho/org.eclipse.paho.mqttv5.client-<version>.jar).
#
# The project's own Maven build uses Eclipse Tycho, which does not run on current
# JDKs. The MQTT 5 client module has no dependencies, so compiling its sources
# directly gives the same classes as the released jar.
#
# Usage:
#   ./avn-build.sh            writes tmp/avn/org.eclipse.paho.mqttv5.client-1.2.6-avn-<commit>.jar
#
# Needs a JDK 9 or later on JAVA_HOME or the PATH (for --release 8). Commit first:
# the jar is named after the commit it was built from, with "-dirty" appended if the
# working tree has changes.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MODULE="org.eclipse.paho.mqttv5.client"
BASE_VERSION="1.2.6"

if [ -n "${JAVA_HOME:-}" ]; then
	JAVAC="$JAVA_HOME/bin/javac"
	JAR="$JAVA_HOME/bin/jar"
else
	JAVAC="javac"
	JAR="jar"
fi

COMMIT="$(git -C "$HERE" rev-parse --short=8 HEAD)"
if [ -n "$(git -C "$HERE" status --porcelain -- "$MODULE/src/main")" ]; then
	COMMIT="$COMMIT-dirty"
fi
VERSION="$BASE_VERSION-avn-$COMMIT"

OUT="$HERE/tmp/avn"
CLASSES="$OUT/classes"
if [ -d "$CLASSES" ]; then
	find "$CLASSES" -type f -delete
fi
mkdir -p "$CLASSES"

find "$HERE/$MODULE/src/main/java" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" --release 8 -nowarn -encoding UTF-8 -d "$CLASSES" "@$OUT/sources.txt"
cp -R "$HERE/$MODULE/src/main/resources/." "$CLASSES/"

mkdir -p "$CLASSES/META-INF"
printf 'Manifest-Version: 1.0\nImplementation-Title: %s\nImplementation-Version: %s\nImplementation-Vendor: LearnHub fork of Eclipse Paho (github.com/LearnHub/paho.mqtt.java)\n' "$MODULE" "$VERSION" > "$OUT/MANIFEST.MF"
"$JAR" cfm "$OUT/$MODULE-$VERSION.jar" "$OUT/MANIFEST.MF" -C "$CLASSES" .

echo "$OUT/$MODULE-$VERSION.jar"
