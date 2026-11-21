#!/usr/bin/env bash
# Banc de test du moteur natif sur PC : compile pixr_engine.cpp pour le PC et lance Bench.java.
# Usage : tools/bench/run.sh [electrons_a_saturation]   (petit nombre = peu de lumière, ex. 400)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which javac)")")")}"
OUT="${TMPDIR:-/tmp}/pixr_bench"; mkdir -p "$OUT"
g++ -O2 -std=c++17 -DPIXR_BENCH -pthread -shared -fPIC -I"$JH/include" -I"$JH/include/linux" \
  "$ROOT/app/src/main/cpp/pixr_engine.cpp" -o "$OUT/libpixr_engine.so"
javac -d "$OUT/classes" "$HERE/com/pixr/app/NativeEngine.java" "$HERE/Bench.java"
java -Xmx2g -cp "$OUT/classes" Bench "$OUT/libpixr_engine.so" "${1:-400}"
