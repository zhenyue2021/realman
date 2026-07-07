#!/bin/sh
# Enable repository git hooks (.githooks/prepare-commit-msg).
# Run once after clone: sh scripts/setup-git-hooks.sh

set -e
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"
git config core.hooksPath .githooks
chmod +x .githooks/prepare-commit-msg 2>/dev/null || true
echo "Git hooks enabled: core.hooksPath=.githooks"
echo "prepare-commit-msg will remove Cursor Co-authored-by trailers."
