# Enable repository git hooks (.githooks/prepare-commit-msg).
# Run once after clone: pwsh scripts/setup-git-hooks.ps1

$ErrorActionPreference = "Stop"
$repoRoot = git rev-parse --show-toplevel 2>$null
if (-not $repoRoot) {
    Write-Error "Not inside a git repository."
}

Set-Location $repoRoot
git config core.hooksPath .githooks

$hook = Join-Path $repoRoot ".githooks\prepare-commit-msg"
if (-not (Test-Path $hook)) {
    Write-Error "Missing hook: $hook"
}

Write-Host "Git hooks enabled: core.hooksPath=.githooks"
Write-Host "prepare-commit-msg will remove Cursor Co-authored-by trailers."
