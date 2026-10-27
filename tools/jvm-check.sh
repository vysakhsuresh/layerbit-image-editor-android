#!/usr/bin/env bash
# Compiles the pure engine (app/src/main/java/com/layerbit/still/engine) and runs its JUnit
# tests with the standalone Kotlin compiler, with NO Android classes on the path.
#
# Two things this proves on every run, on any machine with a JDK and no Android SDK:
#   1. Nothing under engine/ imports android.* - the compile fails if it does. The engine is
#      where every number the app shows and every pixel it changes is decided, and keeping it
#      pure is what lets those decisions be tested in seconds rather than on an emulator.
#   2. The tests under app/src/test/java/com/layerbit/still/engine pass.
#
# Jars are fetched once from Maven Central into ~/.still-kt. CI runs this before Gradle.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
K="${STILL_KT_HOME:-$HOME/.still-kt}"
KV=2.2.21
MC=https://repo.maven.apache.org/maven2
mkdir -p "$K"
is_jar() { [ -s "$1" ] && [ "$(head -c 2 "$1")" = "PK" ]; }
fetch() { # name path
  if ! is_jar "$K/$1"; then
    for try in 1 2 3 4 5; do
      # -L: Maven Central answers with a redirect to its CDN, and a redirect page is not a jar.
      if curl -sSL -f -o "$K/$1" "$MC/$2" && is_jar "$K/$1"; then break; fi
      rm -f "$K/$1"
      sleep $((try * 5))
    done
  fi
  is_jar "$K/$1" || { echo "could not fetch $1"; exit 1; }
}
fetch kotlin-compiler-embeddable.jar "org/jetbrains/kotlin/kotlin-compiler-embeddable/$KV/kotlin-compiler-embeddable-$KV.jar"
fetch kotlin-stdlib.jar "org/jetbrains/kotlin/kotlin-stdlib/$KV/kotlin-stdlib-$KV.jar"
fetch kotlin-script-runtime.jar "org/jetbrains/kotlin/kotlin-script-runtime/$KV/kotlin-script-runtime-$KV.jar"
fetch kotlin-reflect.jar "org/jetbrains/kotlin/kotlin-reflect/$KV/kotlin-reflect-$KV.jar"
fetch kotlin-daemon-embeddable.jar "org/jetbrains/kotlin/kotlin-daemon-embeddable/$KV/kotlin-daemon-embeddable-$KV.jar"
fetch trove4j.jar "org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"
fetch annotations.jar "org/jetbrains/annotations/13.0/annotations-13.0.jar"
fetch coroutines.jar "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar"
fetch junit.jar "junit/junit/4.13.2/junit-4.13.2.jar"
fetch hamcrest.jar "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"

COMPILER_CP="$K/kotlin-compiler-embeddable.jar:$K/kotlin-stdlib.jar:$K/kotlin-script-runtime.jar:$K/kotlin-reflect.jar:$K/kotlin-daemon-embeddable.jar:$K/trove4j.jar:$K/annotations.jar:$K/coroutines.jar"
OUT="$ROOT/tools/out"
rm -rf "$OUT" && mkdir -p "$OUT"

ENGINE="$ROOT/app/src/main/java/com/layerbit/still/engine"
TESTS="$ROOT/app/src/test/java/com/layerbit/still/engine"

if grep -rn "^import android" "$ENGINE"; then
  echo "engine/ must not import android.* (see above)"; exit 1
fi

echo "compiling engine/ and its tests"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -cp "$K/kotlin-stdlib.jar:$K/junit.jar" -d "$OUT" \
  $(find "$ENGINE" "$TESTS" -name '*.kt') 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
[ -d "$OUT/com/layerbit/still/engine" ] || { echo "compile failed"; exit 1; }

CLASSES=$(cd "$OUT" && find com/layerbit/still/engine -name '*Test.class' | sed 's#/#.#g; s#\.class$##' | tr '\n' ' ')
echo "running: $CLASSES"
java -cp "$OUT:$K/kotlin-stdlib.jar:$K/junit.jar:$K/hamcrest.jar" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v JAVA_TOOL_OPTIONS
