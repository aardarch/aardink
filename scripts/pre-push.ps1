#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Pre-push verification script for Aardink.
    Mirrors the CI pipeline checks so issues are caught locally before push.

.DESCRIPTION
    Runs secret scan, licence headers, formatting, lint, unit tests (JVM + wasmJs),
    and (optionally) the sample app build and consumer smoke test.
    Autofixes (Spotless) are applied by default. Use -NoFix to run check-only.
    Use -SkipBuild to skip the sample APK build (faster iteration).
    Use -SkipTests to skip unit tests.
    Use -SkipWasm to skip the wasmJs browser tests, which need a local Chrome
    (Karma finds it via CHROME_BIN, or on PATH).

.EXAMPLE
    .\scripts\pre-push.ps1
    .\scripts\pre-push.ps1 -NoFix
    .\scripts\pre-push.ps1 -SkipBuild -SkipTests
#>
param(
    [switch]$NoFix,
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$SkipWasm
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

# ── Resolve paths ───────────────────────────────────────────────────
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Gradlew = Join-Path $ProjectRoot 'gradlew.bat'

if (-not (Test-Path $Gradlew)) {
    Write-Error "gradlew.bat not found at $Gradlew — run this script from the aardink repo root."
    exit 1
}

Push-Location $ProjectRoot
try {

    # ── Colour helpers ──────────────────────────────────────────────────
    function Write-Step { param([string]$Msg) Write-Host "`n==> $Msg" -ForegroundColor Cyan }
    function Write-Pass { param([string]$Msg) Write-Host "    PASS  $Msg" -ForegroundColor Green }
    function Write-Warn { param([string]$Msg) Write-Host "    WARN  $Msg" -ForegroundColor Yellow }
    function Write-Fail { param([string]$Msg) Write-Host "    FAIL  $Msg" -ForegroundColor Red }

    # ── Track results ───────────────────────────────────────────────────
    $Results = [ordered]@{}
    $Failures = 0

    function Invoke-Check {
        param(
            [string]$Name,
            [scriptblock]$Action
        )
        Write-Step $Name
        $global:LASTEXITCODE = 0
        try {
            & $Action
            if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "Exit code $LASTEXITCODE" }
            Write-Pass $Name
            $Results[$Name] = 'PASS'
        }
        catch {
            Write-Fail "$Name — $_"
            $Results[$Name] = 'FAIL'
            $script:Failures++
        }
    }

    # ── 1. Secret / credential scan ────────────────────────────────────
    Invoke-Check 'Secret scan' {
        $Patterns = @(
            '(?i)SIGNING_STORE_PASSWORD\s*=\s*\S+',
            '(?i)SIGNING_KEY_PASSWORD\s*=\s*\S+',
            '(?i)api[_-]?key\s*[:=]\s*["\x27][A-Za-z0-9]{16,}',
            '(?i)client[_-]?secret\s*[:=]\s*["\x27]\S+',
            '(?i)mavenCentralPassword\s*=\s*\S+',
            '(?i)signingInMemoryKeyPassword\s*=\s*\S+'
        )

        $Hits = @()
        foreach ($Pattern in $Patterns) {
            $Found = Get-ChildItem -Path $ProjectRoot -Recurse -Include '*.kt', '*.kts', '*.properties', '*.xml', '*.json', '*.toml' |
                Where-Object { $_.FullName -notmatch '[\\/](build|\.gradle|\.idea|\.kotlin)[\\/]' } |
                Where-Object { $_.Name -ne 'local.properties' -and $_.Name -ne 'keystore.properties' } |
                Select-String -Pattern $Pattern -List
            if ($Found) { $Hits += $Found }
        }

        if ($Hits.Count -gt 0) {
            $Hits | ForEach-Object { Write-Warn "  Possible secret: $($_.Path):$($_.LineNumber)" }
            throw "$($Hits.Count) potential secret(s) found — review before pushing."
        }
    }

    # ── 2. License header check (Apache 2.0 on every Kotlin source) ────
    Invoke-Check 'Apache 2.0 license headers' {
        $SourceRoots = @(
            (Join-Path $ProjectRoot 'editor' 'src'),
            (Join-Path $ProjectRoot 'languages' 'src'),
            (Join-Path $ProjectRoot 'languages-lsp' 'src'),
            (Join-Path $ProjectRoot 'editor-web' 'src'),
            (Join-Path $ProjectRoot 'sample-desktop' 'src'),
            (Join-Path $ProjectRoot 'sample-web' 'src'),
            (Join-Path $ProjectRoot 'sample' 'src'),
            (Join-Path $ProjectRoot 'tools' 'consumer-smoke' 'src')
        )
        $Sources = $SourceRoots |
            Where-Object { Test-Path $_ } |
            ForEach-Object { Get-ChildItem -Path $_ -Recurse -Include '*.kt' -ErrorAction SilentlyContinue }
        $Missing = @()
        foreach ($File in $Sources) {
            $Head = Get-Content $File.FullName -TotalCount 20 -ErrorAction SilentlyContinue
            if (-not ($Head -match 'Apache License' -or $Head -match 'Licensed under the Apache')) {
                $Missing += $File.FullName.Substring($ProjectRoot.Length + 1)
            }
        }
        if ($Missing.Count -gt 0) {
            $Missing | ForEach-Object { Write-Warn "  Missing Apache header: $_" }
            throw "$($Missing.Count) source file(s) missing the Apache 2.0 license header."
        }
    }

    # ── 3. Spotless (format check or auto-fix) ─────────────────────────
    # Every published library, in one place so a new module only needs adding here.
    $Libs = @(':editor', ':languages', ':languages-lsp')
    # wasmJs-only libraries: no JVM target, so they join the Spotless and browser-test steps
    # but not the JVM one.
    $WebLibs = @(':editor-web')
    $SpotlessModules = $Libs + $WebLibs + ':sample' + ':sample-web'
    $SpotlessTasks = if ($NoFix) {
        $SpotlessModules | ForEach-Object { "${_}:spotlessCheck" }
    } else {
        $SpotlessModules | ForEach-Object { "${_}:spotlessApply" }
    }
    $SpotlessLabel = if ($NoFix) { 'Spotless check' } else { 'Spotless apply (auto-fix)' }

    Invoke-Check $SpotlessLabel {
        & $Gradlew @SpotlessTasks --quiet 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Spotless failed' }
    }

    # ── 4. Android Lint ─────────────────────────────────────────────────
    # :sample only. com.android.kotlin.multiplatform.library registers no `lint` task, so the
    # three libraries have no lint to run (verified with `gradlew :editor:tasks --all`).
    Invoke-Check 'Android lint' {
        & $Gradlew ':sample:lintDebug' --quiet 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Lint failed' }
    }

    # ── 5. Unit tests ───────────────────────────────────────────────────
    Invoke-Check 'ABI check' {
        & $Gradlew 'checkAbiAll' --quiet 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Public ABI differs from the committed dumps - run `gradlew updateAbiAll` and review the diff' }
    }

    # `jvmTest`, not `test`: a KMP module has no aggregate `test` task. The full aggregate is
    # `allTests`, but that also pulls in the browser run, which is gated separately below.
    if (-not $SkipTests) {
        Invoke-Check 'Unit tests (JVM)' {
            $Tasks = $Libs | ForEach-Object { "${_}:jvmTest" }
            & $Gradlew @Tasks --quiet 2>&1 | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'JVM tests failed' }
        }

        if (-not $SkipWasm) {
            Invoke-Check 'Unit tests (wasmJs browser)' {
                $Tasks = ($Libs + $WebLibs) | ForEach-Object { "${_}:wasmJsBrowserTest" }
                & $Gradlew @Tasks --quiet 2>&1 | Out-Host
                if ($LASTEXITCODE -ne 0) { throw 'wasmJs browser tests failed (is Chrome installed? set CHROME_BIN)' }
            }

            # The npm package in a real Vite app, in headless Chrome. Needs pnpm on PATH.
            Invoke-Check 'Web npm package + Vite smoke test' {
                & $Gradlew ':sample-web:npmPackage' ':sample-web:verifyExportsMatchTemplate' --quiet 2>&1 | Out-Host
                if ($LASTEXITCODE -ne 0) { throw 'Building the npm package failed' }
                Push-Location (Join-Path $ProjectRoot 'tools' 'vite-smoke')
                try {
                    # --force: the package is a file: dependency, copied at install time, so a
                    # plain install would keep testing the previous build.
                    pnpm install --force --frozen-lockfile 2>&1 | Out-Host
                    if ($LASTEXITCODE -ne 0) { throw 'pnpm install failed (is pnpm installed?)' }
                    pnpm smoke 2>&1 | Out-Host
                    if ($LASTEXITCODE -ne 0) { throw 'Vite smoke test failed - see tools/vite-smoke/dist/smoke.png' }
                } finally {
                    Pop-Location
                }
            }
        }
    }

    # ── 6. Sample app build (sanity check) ─────────────────────────────
    if (-not $SkipBuild) {
        Invoke-Check 'Build sample app (debug)' {
            & $Gradlew ':sample:assembleDebug' --quiet 2>&1 | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'Sample build failed' }
        }

        # Roborazzi in verify mode - the Android zero-regression gate every migration PR is
        # measured against. Nothing else in this script would notice a pixel change.
        Invoke-Check 'Screenshot regression (Roborazzi verify)' {
            & $Gradlew ':sample:verifyRoborazziDebug' --quiet 2>&1 | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'Screenshot verification failed - see sample/build/outputs/roborazzi/' }
        }
    }

    # ── 7. Local Maven publish + consumer smoke test ───────────────────
    if (-not $SkipBuild) {
        Invoke-Check 'Publish to mavenLocal + consumer smoke test' {
            & (Join-Path $PSScriptRoot 'verify-consumer.ps1')
            if ($LASTEXITCODE -ne 0) { throw 'verify-consumer.ps1 failed' }
        }
    }

    # ── Summary ─────────────────────────────────────────────────────────
    Write-Host "`n" -NoNewline
    Write-Host ('=' * 50) -ForegroundColor DarkGray
    Write-Host '  Pre-push results' -ForegroundColor Cyan
    Write-Host ('=' * 50) -ForegroundColor DarkGray

    foreach ($Entry in $Results.GetEnumerator()) {
        $Colour = if ($Entry.Value -eq 'PASS') { 'Green' } else { 'Red' }
        Write-Host "  [$($Entry.Value)]  $($Entry.Key)" -ForegroundColor $Colour
    }

    Write-Host ('=' * 50) -ForegroundColor DarkGray

    if ($Failures -gt 0) {
        Write-Host "`n  $Failures check(s) failed. Fix issues before pushing.`n" -ForegroundColor Red
        exit 1
    }
    else {
        Write-Host "`n  All checks passed. Safe to push.`n" -ForegroundColor Green
        exit 0
    }

}
finally {
    Pop-Location
}
