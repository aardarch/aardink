#Requires -Version 7.0
<#
.SYNOPSIS
    Push the staged release commit and tag, triggering the Maven Central publish workflow.
.DESCRIPTION
    Run after ./create-release.ps1. Verifies HEAD is a release commit whose tag matches
    VERSION_NAME, then pushes main and the tag to origin.
#>

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $RepoRoot
try {
    . (Join-Path $PSScriptRoot 'Release-Common.ps1')

    if (-not (Test-Path .git)) { throw "Not in the root of a git repository." }
    $branch = git rev-parse --abbrev-ref HEAD
    if ($branch -ne 'main') { throw "You are on branch '$branch'. Switch to 'main' first." }

    $headSubject = git log -1 --pretty=format:%s
    if ($headSubject -notmatch '^chore\(release\): v(\d+\.\d+\.\d+(?:[-+].+)?)$') {
        throw "HEAD is not a release commit. Subject was: '$headSubject'. Run ./create-release.ps1 first."
    }
    $commitVersion = $Matches[1]
    $expectedTag = "v$commitVersion"

    $tagsAtHead = git tag --points-at HEAD
    if ($tagsAtHead -notcontains $expectedTag) {
        throw "Expected tag $expectedTag at HEAD but found: $($tagsAtHead -join ', ')."
    }

    $propsVersion = Get-VersionFromGradleProperties -RepoRoot $RepoRoot
    if ($propsVersion -ne $commitVersion) {
        throw "VERSION_NAME ($propsVersion) does not match release commit version ($commitVersion)."
    }

    Write-Host "Pre-push checks passed for $expectedTag." -ForegroundColor Green
    Write-Host "  HEAD:           $(git rev-parse --short HEAD) $headSubject"
    Write-Host "  VERSION_NAME:   $propsVersion"
    Write-Host ""
    $confirm = Read-Host "Push main and $expectedTag to origin? [y/N]"
    if ($confirm -notmatch '^(y|Y|yes|YES)$') {
        Write-Host "Aborted." -ForegroundColor Yellow
        exit 0
    }

    Write-Host "Pushing main..." -ForegroundColor Cyan
    git push origin main
    if ($LASTEXITCODE -ne 0) { throw "git push origin main failed." }

    Write-Host "Pushing $expectedTag..." -ForegroundColor Cyan
    git push origin $expectedTag
    if ($LASTEXITCODE -ne 0) { throw "git push origin $expectedTag failed." }

    Write-Host ""
    Write-Host "Pushed. Watch the release workflow:" -ForegroundColor Green
    $remoteUrl = git config --get remote.origin.url
    if ($remoteUrl -match 'github\.com[:/](.+?)(?:\.git)?$') {
        Write-Host "  https://github.com/$($Matches[1])/actions" -ForegroundColor White
    }
} finally {
    Pop-Location
}
