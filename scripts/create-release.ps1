#Requires -Version 7.0
<#
.SYNOPSIS
    Stage a release locally: bump VERSION_NAME, finalize CHANGELOG, commit, and tag.
.DESCRIPTION
    Does NOT push. Inspect the resulting commit and tag with `git show vX.Y.Z`, then run
    `./release.ps1` to push (which is what triggers the Maven Central publish workflow).

    Steps:
      1. Sanity gates: on main, in sync with origin/main, working tree clean.
      2. Determine target version (from -Version, or interactive prompt with the
         conventional-commit-derived suggestion as default).
      3. Reject if the tag already exists locally or on origin.
      4. Run ./pre-push.ps1 -NoFix (full CI-equivalent gate).
      5. Bump VERSION_NAME in gradle.properties.
      6. In CHANGELOG.md: rename ## [Unreleased] to ## [X.Y.Z] - YYYY-MM-DD and insert
         a fresh empty ## [Unreleased] above it.
      7. Stage gradle.properties + CHANGELOG.md, commit `chore(release): vX.Y.Z`,
         create annotated tag vX.Y.Z with the extracted CHANGELOG section as the message.
.PARAMETER Version
    Target version, with or without leading 'v' (e.g. 0.2.0 or v0.2.0). If omitted,
    you'll be prompted with the suggested next version as the default.
.PARAMETER AllowEmptyChangelog
    Permit cutting a release when the [Unreleased] section has no entries. By default
    this fails so you remember to run ./update-changelog.ps1 first.
.PARAMETER SkipPrePush
    Skip the ./pre-push.ps1 -NoFix step. Use sparingly; CI runs the same checks.
#>

[CmdletBinding()]
param(
    [string]$Version,
    [switch]$AllowEmptyChangelog,
    [switch]$SkipPrePush
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $RepoRoot
try {
    . (Join-Path $PSScriptRoot 'Release-Common.ps1')

    # --- Gates ---
    if (-not (Test-Path .git)) {
        throw "Not in the root of a git repository."
    }
    $branch = git rev-parse --abbrev-ref HEAD
    if ($branch -ne 'main') {
        throw "You are on branch '$branch'. Switch to 'main' first."
    }
    Write-Host "Fetching latest from origin..." -ForegroundColor Cyan
    git fetch origin --tags
    if ([int](git rev-list --count "HEAD..origin/main") -gt 0) {
        throw "Local main is behind origin/main. Pull first."
    }
    if ([int](git rev-list --count "origin/main..HEAD") -gt 0) {
        throw "Local main is ahead of origin/main. Push existing commits first (or rebase)."
    }
    if (git status --porcelain) {
        throw "Working tree is not clean. Commit or stash before releasing."
    }

    # --- Target version ---
    $currentVersion = Get-VersionFromGradleProperties -RepoRoot $RepoRoot
    Write-Host "Current VERSION_NAME: $currentVersion" -ForegroundColor Cyan

    $latestTag = Get-LatestSemverTag
    $commits = @(Get-CommitsSinceTag -Tag $latestTag)
    $suggested = $null
    if ($commits.Count -gt 0) {
        $cat = Get-CategorizedCommits -Commits $commits
        $suggested = Get-SuggestedNextVersion -CurrentVersion $currentVersion `
            -HasBreaking $cat.HasBreaking -HasFeature $cat.HasFeature
    }

    if (-not $Version) {
        $defaultVersion = if ($suggested) { $suggested.Version } else { $currentVersion }
        $reason = if ($suggested) { " ($($suggested.Reason))" } else { '' }
        $inputVal = Read-Host "Target version [default v$defaultVersion$reason]"
        $Version = if ([string]::IsNullOrWhiteSpace($inputVal)) { $defaultVersion } else { $inputVal }
    }
    $Version = $Version -replace '^v', ''
    if ($Version -notmatch '^\d+\.\d+\.\d+(?:[-+].+)?$') {
        throw "Version '$Version' is not a valid semver."
    }
    $tag = "v$Version"

    if (git tag --list $tag) {
        throw "Tag $tag already exists locally."
    }
    if (git ls-remote --tags origin "refs/tags/$tag") {
        throw "Tag $tag already exists on origin."
    }

    # --- CHANGELOG sanity ---
    $changelogPath = Join-Path $RepoRoot 'CHANGELOG.md'
    if (-not (Test-Path $changelogPath)) { throw "CHANGELOG.md not found." }
    $unreleased = Get-ChangelogSection -ChangelogPath $changelogPath -Version 'Unreleased'
    if ($null -eq $unreleased) {
        throw "No ## [Unreleased] section found in CHANGELOG.md."
    }
    if ([string]::IsNullOrWhiteSpace($unreleased) -and -not $AllowEmptyChangelog) {
        throw "[Unreleased] section is empty. Run ./update-changelog.ps1 first, or pass -AllowEmptyChangelog."
    }

    # --- Pre-push checks ---
    if (-not $SkipPrePush) {
        Write-Host ""
        Write-Host "Running ./pre-push.ps1 -NoFix..." -ForegroundColor Cyan
        & (Join-Path $PSScriptRoot 'pre-push.ps1') -NoFix
        if ($LASTEXITCODE -ne 0) {
            throw "pre-push.ps1 failed (exit $LASTEXITCODE). Fix and re-run."
        }
    } else {
        Write-Host "Skipping pre-push checks (-SkipPrePush)." -ForegroundColor Yellow
    }

    # --- Mutate: gradle.properties ---
    Write-Host ""
    Write-Host "Bumping VERSION_NAME: $currentVersion -> $Version" -ForegroundColor Cyan
    Set-VersionInGradleProperties -RepoRoot $RepoRoot -NewVersion $Version

    # --- Mutate: CHANGELOG ---
    $today = (Get-Date).ToString('yyyy-MM-dd')
    Write-Host "Cutting CHANGELOG: [Unreleased] -> [$Version] - $today" -ForegroundColor Cyan
    $content = Get-Content $changelogPath -Raw
    $replacement = "## [Unreleased]`n`n## [$Version] - $today`n"
    $newContent = $content -replace '(?m)^## \[Unreleased\]\s*$', $replacement
    Set-Content -Path $changelogPath -Value $newContent -NoNewline

    # --- Extract released section for the tag annotation ---
    $releaseNotes = Get-ChangelogSection -ChangelogPath $changelogPath -Version $Version
    if ([string]::IsNullOrWhiteSpace($releaseNotes)) {
        $releaseNotes = "Release $tag"
    }

    # --- Commit + tag ---
    git add gradle.properties CHANGELOG.md
    git commit -m "chore(release): $tag"
    if ($LASTEXITCODE -ne 0) { throw "git commit failed." }

    $tagMessage = "Release $tag`n`n$releaseNotes"
    $tmp = New-TemporaryFile
    try {
        Set-Content -Path $tmp -Value $tagMessage -NoNewline
        git tag -a $tag -F $tmp
        if ($LASTEXITCODE -ne 0) { throw "git tag failed." }
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }

    Write-Host ""
    Write-Host "Release $tag staged locally." -ForegroundColor Green
    Write-Host "  Review:  git show $tag" -ForegroundColor White
    Write-Host "  Push:    ./release.ps1" -ForegroundColor White
    Write-Host "  Abort:   git tag -d $tag; git reset --hard HEAD~1" -ForegroundColor DarkGray
} finally {
    Pop-Location
}
