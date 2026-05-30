#!/usr/bin/env bash
set -e

SRC=src
OUT=out/classes
JAR=navier.jar

mkdir -p "$OUT"
find "$SRC" -name "*.java" | xargs javac --release 21 -d "$OUT"
printf "Main-Class: Main\n" > "$OUT/MANIFEST.MF"
jar --create --file="$JAR" --manifest="$OUT/MANIFEST.MF" -C "$OUT" .
echo "Built $JAR"
echo "Run: java -jar navier.jar <torrent-file> [-o <output-dir>] [-t <tracker-url>]"
