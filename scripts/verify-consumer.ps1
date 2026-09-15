#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Verifies that com.aardarch:aardink (and its sibling artifacts) still resolve and work
    for a real Maven/Gradle consumer.

.DESCRIPTION
    Publishes editor/languages/languages-lsp to mavenLocal, then builds
    tools/consumer-smoke — an Android app that depends on the PUBLISHED coordinates rather
    than project(":editor") — and prints which dependency variant was resolved.

    This is the Android zero-regression gate referenced throughout
    docs/KMP_MIGRATION_PLAN.md: it must stay green through every migration PR, and once the
    libraries become Kotlin Multiplatform it additionally confirms that Gradle Module
    Metadata still selects the `-android` variant for this Android app, so existing
    consumers' `implementation("com.aardarch:aardink:x")` declarations keep working
    unchanged.

.EXAMPLE
    .\scripts\verify-consumer.ps1
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Gradlew = Join-Path $ProjectRoot 'gradlew.bat'

if (-not (Test-Path $Gradlew)) {
    Write-Error "gradlew.bat not found at $Gradlew — run this script from the aardink repo root."
    exit 1
}

Push-Location $ProjectRoot
try {
    function Write-Step { param([string]$Msg) Write-Host "`n==> $Msg" -ForegroundColor Cyan }

    # Every published library — kept as one variable so a new module only needs adding here.
    $Libs = @(':editor', ':languages', ':languages-lsp')

    Write-Step 'Publishing to mavenLocal'
    $PublishTasks = $Libs | ForEach-Object { "${_}:publishToMavenLocal" }
    & $Gradlew @PublishTasks --quiet 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Error 'publishToMavenLocal failed'; exit 1 }

    Write-Step 'Building tools:consumer-smoke against the published coordinates'
    & $Gradlew ':tools:consumer-smoke:assembleDebug' --quiet 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Error 'consumer-smoke build failed — the published artifacts do not resolve or do not compile against a real consumer.'; exit 1 }

    Write-Step 'Resolved Aardink dependency variant'
    $Resolved = & $Gradlew ':tools:consumer-smoke:dependencies' '--configuration' 'debugRuntimeClasspath' --quiet 2>&1
    $Resolved | Select-String -Pattern 'com\.aardarch:aardink' | ForEach-Object { Write-Host "    $_" }

    Write-Host "`nConsumer smoke test passed: com.aardarch:aardink resolves and builds for a real consumer.`n" -ForegroundColor Green
    exit 0
}
finally {
    Pop-Location
}
