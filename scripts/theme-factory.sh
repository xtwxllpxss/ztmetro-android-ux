#!/usr/bin/env bash
# ztMetro theme factory — one entry point for analyse / generate / apply.
#
#   repo/scripts/theme-factory.sh analyse  --apk Etar.apk [--out app-profile.json]
#   repo/scripts/theme-factory.sh generate --profile app-profile.json --dry-run
#   repo/scripts/theme-factory.sh generate --profile app-profile.json --out overlay/
#   repo/scripts/theme-factory.sh apply    --profile app-profile.json --out overlay/
#
# Env: ANDROID_SDK, ANDROID_JDK, BUILD_TOOLS_VERSION, PYTHON.
# The Python package lives in repo/tools/theme-factory/theme_factory.
set -euo pipefail

_self="${BASH_SOURCE[0]:-}"
if [ -n "$_self" ]; then
  REPO_ROOT="$(cd "$(dirname "$_self")/.." && pwd)"
else
  REPO_ROOT="$(pwd)"
fi

PY="${PYTHON:-python3}"
export PYTHONPATH="$REPO_ROOT/tools/theme-factory${PYTHONPATH:+:$PYTHONPATH}"

if [ $# -eq 0 ]; then
  "$PY" -m theme_factory --help
  exit 0
fi

exec "$PY" -m theme_factory "$@"
