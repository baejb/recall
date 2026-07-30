#!/usr/bin/env bash
# git 훅 활성화 — 각 개발자가 클론 후 1회 실행한다.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"

git config core.hooksPath .githooks
chmod +x .githooks/* 2>/dev/null || true

echo "✓ git hooks 활성화 완료 (core.hooksPath=.githooks)"
echo "  이제 커밋 시 .githooks/pre-commit 이 포맷·금지패턴을 검사합니다."
