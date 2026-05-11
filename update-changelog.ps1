#Requires -Version 7.0
<#
.SYNOPSIS
    Updates the [Unreleased] section of CHANGELOG.md with categorized entries derived from
    commits since the last semver tag.
.DESCRIPTION
    - Verifies you are on main and in sync with origin/main.
    - Detects the latest semver tag (vX.Y.Z) and the commits since.
    - Classifies commits using Keep a Changelog categories (shared logic in
      scripts/Release-Common.ps1).
    - Replaces the [Unreleased] section in CHANGELOG.md with the categorized list.
    - Suggests the next version based on conventional-commit signals.
    - Idempotent.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptDir = $PSScriptRoot
Push-Location $ScriptDir
try {
    . (Join-Path $ScriptDir 'scripts/Release-Common.ps1')

    if (-not (Test-Path .git)) {
        Write-Host "Error: Not in the root of a git repository." -ForegroundColor Red
        exit 1
    }

    $currentBranch = git rev-parse --abbrev-ref HEAD
    if ($currentBranch -ne 'main') {
        Write-Host "Error: You are on branch '$currentBranch'. Switch to 'main' first." -ForegroundColor Red
        exit 1
    }

    Write-Host "Fetching latest from origin..." -ForegroundColor Cyan
    git fetch origin --tags

    $behind = git rev-list --count "HEAD..origin/main"
    if ([int]$behind -gt 0) {
        Write-Host "Error: Local main is $behind commit(s) behind origin/main. Pull first." -ForegroundColor Red
        exit 1
    }
    $ahead = git rev-list --count "origin/main..HEAD"
    if ([int]$ahead -gt 0) {
        Write-Host "Local main is $ahead commit(s) ahead of origin/main." -ForegroundColor Yellow
    }
    if (git status --porcelain) {
        Write-Host "Warning: Working tree is not clean." -ForegroundColor Yellow
    }
    Write-Host "On main, in sync with origin." -ForegroundColor Green
    Write-Host ""

    $latestTag = Get-LatestSemverTag
    if ($latestTag) {
        Write-Host "Latest tag: $latestTag" -ForegroundColor Cyan
        $currentVersion = $latestTag -replace '^v', ''
    } else {
        Write-Host "No existing semver tags found." -ForegroundColor Yellow
        $currentVersion = '1.0.0'
    }

    $commits = Get-CommitsSinceTag -Tag $latestTag
    if ($commits.Count -eq 0) {
        Write-Host "No new releasable commits since $latestTag. Nothing to do." -ForegroundColor Yellow
        exit 0
    }
    Write-Host "Found $($commits.Count) commit(s) to process." -ForegroundColor Cyan

    $result = Get-CategorizedCommits -Commits $commits

    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.AppendLine("## [Unreleased]")
    [void]$sb.AppendLine()
    foreach ($cat in $result.Categories.Keys) {
        if ($result.Categories[$cat].Count -gt 0) {
            [void]$sb.AppendLine("### $cat")
            [void]$sb.AppendLine()
            foreach ($item in $result.Categories[$cat]) { [void]$sb.AppendLine($item) }
            [void]$sb.AppendLine()
        }
    }

    $changelogPath = Join-Path $ScriptDir 'CHANGELOG.md'
    if (-not (Test-Path $changelogPath)) {
        Write-Host "Error: Changelog not found at $changelogPath" -ForegroundColor Red
        exit 1
    }

    $content = Get-Content $changelogPath -Raw
    if ($content -notmatch '## \[Unreleased\]') {
        Write-Host "Error: No ## [Unreleased] section found in $changelogPath" -ForegroundColor Red
        exit 1
    }
    $newContent = $content -replace '(?ms)^## \[Unreleased\].*?(?=^## \[|\z)', $sb.ToString()
    Set-Content -Path $changelogPath -Value $newContent -NoNewline

    $next = Get-SuggestedNextVersion -CurrentVersion $currentVersion `
        -HasBreaking $result.HasBreaking -HasFeature $result.HasFeature

    Write-Host ""
    Write-Host "Changelog updated: $changelogPath" -ForegroundColor Green
    foreach ($cat in $result.Categories.Keys) {
        $count = $result.Categories[$cat].Count
        if ($count -gt 0) { Write-Host "  ${cat}: $count entries" }
    }
    Write-Host ""
    Write-Host "Suggested next version: v$($next.Version) ($($next.Reason))" -ForegroundColor Cyan
} finally {
    Pop-Location
}
