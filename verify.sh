#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "==> tests"
./gradlew test

echo
echo "==> self-check"
./gradlew selfCheck
