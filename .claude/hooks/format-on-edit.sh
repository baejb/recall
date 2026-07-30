#!/usr/bin/env bash
# Claude Code PostToolUse 훅: 편집/생성된 파일을 자동 포맷한다.
# stdin 으로 도구 정보(JSON)를 받아 file_path 를 뽑고 확장자별로 포맷한다.
# - 프론트(ts/tsx/js/jsx/json/css): frontend 의 prettier (설치돼 있을 때만)
# - Java: gradle spotless 는 편집마다 돌리기엔 느려 여기선 건너뛰고 pre-commit 에서 검사
set -euo pipefail

input=$(cat)
file=$(printf '%s' "$input" \
  | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]+"' \
  | head -1 \
  | sed -E 's/.*"file_path"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')

[ -z "${file:-}" ] && exit 0
[ ! -f "$file" ] && exit 0

case "$file" in
  *.ts|*.tsx|*.js|*.jsx|*.json|*.css)
    if [ -x frontend/node_modules/.bin/prettier ]; then
      frontend/node_modules/.bin/prettier --write "$file" >/dev/null 2>&1 || true
    fi
    ;;
esac

exit 0
