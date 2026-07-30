#!/usr/bin/env bash
# Source this before building:  source scripts/env.sh
#
# Homebrew installs openjdk@21 keg-only, so it is not on PATH by default and
# `java -version` will still report the system JDK until you do this.

_candidates=(
  "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
)

for _c in "${_candidates[@]}"; do
  if [ -x "$_c/bin/java" ]; then
    export JAVA_HOME="$_c"
    export PATH="$JAVA_HOME/bin:$PATH"
    break
  fi
done

if [ -z "${JAVA_HOME:-}" ]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)" && export JAVA_HOME \
      && export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JDK 21 not found. Install it with: brew install openjdk@21" >&2
else
  echo "JAVA_HOME=$JAVA_HOME"
  java -version
fi

unset _candidates _c
