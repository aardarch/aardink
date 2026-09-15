#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Captures screenshots of the sample app's start screen and every language sample screen,
    in every bundled editor theme.

.DESCRIPTION
    Runs the Roborazzi/Robolectric screenshot test (SampleScreenshotTest) via Gradle, which
    renders each page to PNG on the JVM (no device or emulator needed), then copies the
    results into the repo's screenshots/ folder.

.EXAMPLE
    .\scripts\capture-screenshots.ps1
    .\scripts\capture-screenshots.ps1 -KeepExisting
#>
param(
    [switch]$KeepExisting
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Msg) Write-Host "`n==> $Msg" -ForegroundColor Cyan }

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Gradlew = Join-Path $ProjectRoot 'gradlew.bat'

if (-not (Test-Path $Gradlew)) {
    Write-Error "gradlew.bat not found at $Gradlew — run this script from the aardink repo root."
    exit 1
}

$BuildOutput = Join-Path $ProjectRoot 'sample\build\outputs\screenshots'
$DestDir = Join-Path $ProjectRoot 'screenshots'

Push-Location $ProjectRoot
try {
    Write-Step 'Running SampleScreenshotTest...'
    & $Gradlew ':sample:recordRoborazziDebug' --tests '*.SampleScreenshotTest' --no-daemon --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }

    $built = @(Get-ChildItem "$BuildOutput\*.png" -ErrorAction SilentlyContinue)
    if ($built.Count -eq 0) {
        throw "No screenshots generated at $BuildOutput"
    }

    Write-Step "Copying screenshots to $DestDir"
    New-Item -ItemType Directory -Path $DestDir -Force | Out-Null
    if (-not $KeepExisting) {
        Get-ChildItem "$DestDir\*.png" -ErrorAction SilentlyContinue | Remove-Item -Force
    }
    Copy-Item "$BuildOutput\*.png" -Destination $DestDir -Force

    $files = @(Get-ChildItem "$DestDir\*.png" | Sort-Object Name)
    Write-Host "`n==> Done! $($files.Count) screenshots in $DestDir`:" -ForegroundColor Green
    $files | ForEach-Object { Write-Host "    $($_.Name)" }
}
finally {
    Pop-Location
}
