#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Captures screenshots of the sample app's start screen and every language sample screen,
    in every bundled editor theme.

.DESCRIPTION
    Runs the Roborazzi/Robolectric screenshot test (SampleScreenshotTest) via Gradle, which
    renders each page to PNG on the JVM (no device or emulator needed) directly into the
    repo's screenshots/ folder - the same baselines :sample:verifyRoborazziDebug compares
    against in CI. Review the resulting git diff before committing.

.EXAMPLE
    .\scripts\capture-screenshots.ps1
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "`n==> $Msg" -ForegroundColor Cyan }

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Gradlew = Join-Path $ProjectRoot 'gradlew.bat'

if (-not (Test-Path $Gradlew)) {
    Write-Error "gradlew.bat not found at $Gradlew — run this script from the aardink repo root."
    exit 1
}

$DestDir = Join-Path $ProjectRoot 'screenshots'

Push-Location $ProjectRoot
try {
    Write-Step 'Running SampleScreenshotTest...'
    & $Gradlew ':sample:recordRoborazziDebug' --tests '*.SampleScreenshotTest' --no-daemon --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }

    # SampleScreenshotTest writes straight into $DestDir, and clears each theme's stale PNGs
    # itself when recording, so there is nothing to copy or pre-delete here.
    $files = @(Get-ChildItem "$DestDir\*.png" -ErrorAction SilentlyContinue | Sort-Object Name)
    if ($files.Count -eq 0) {
        throw "No screenshots generated at $DestDir"
    }

    Write-Host "`n==> Done! $($files.Count) screenshots in $DestDir`:" -ForegroundColor Green
    $files | ForEach-Object { Write-Host "    $($_.Name)" }
}
finally {
    Pop-Location
}
