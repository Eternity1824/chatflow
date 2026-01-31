#!/usr/bin/env bash
set -euo pipefail

CONFIG_PATH="${CHATFLOW_CONFIG:-config/client.yml}"
if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "Config not found: $CONFIG_PATH" >&2
  exit 1
fi

JVM_OPTS=$(grep '^jvmOpts:' "$CONFIG_PATH" | sed -E 's/^jvmOpts:[[:space:]]*"?([^"]*)"?/\\1/')

if [[ -z "${JVM_OPTS// }" ]]; then
  JVM_OPTS=""
fi

./gradlew :client-part1:classes
java ${JVM_OPTS} -cp "client-part1/build/classes/java/main:client-part1/build/resources/main" \
  com.chatflow.client.ChatClient --config="$CONFIG_PATH"
