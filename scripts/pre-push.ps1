#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Pre-push verification script for Aardink.
    Mirrors the CI pipeline checks so issues are caught locally before push.

.DESCRIPTION
    Runs secret scan, formatting, lint, unit tests, API compatibility check,
    and (optionally) the sample app build.
    Autofixes (Spotless) are applied by default. Use -NoFix to run check-only.
    Use -SkipBuild to skip the sample APK build (faster iteration).
    Use -SkipTests to skip unit tests.

.EXAMPLE
    .\scripts\pre-push.ps1
    .\scripts\pre-push.ps1 -NoFix
    .\scripts\pre-push.ps1 -SkipBuild -SkipTests
#>
param(
    [switch]$NoFix,
    [switch]$SkipBuild,
    [switch]$SkipTests
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
            (Join-Path $ProjectRoot 'sample' 'src')
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
    $SpotlessTasks = if ($NoFix) {
        @(':editor:spotlessCheck', ':languages:spotlessCheck', ':sample:spotlessCheck')
    } else {
        @(':editor:spotlessApply', ':languages:spotlessApply', ':sample:spotlessApply')
    }
    $SpotlessLabel = if ($NoFix) { 'Spotless check' } else { 'Spotless apply (auto-fix)' }

    Invoke-Check $SpotlessLabel {
        & $Gradlew @SpotlessTasks --quiet 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Spotless failed' }
    }

    # ── 4. Android Lint ─────────────────────────────────────────────────
    Invoke-Check 'Android lint' {
        & $Gradlew ':editor:lint' ':languages:lint' ':sample:lintDebug' --quiet 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Lint failed' }
    }

    # ── 5. Unit tests ───────────────────────────────────────────────────
    if (-not $SkipTests) {
        Invoke-Check 'Unit tests' {
            & $Gradlew ':editor:test' ':languages:test' --quiet 2>&1 | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
        }
    }

    # ── 6. Sample app build (sanity check) ─────────────────────────────
    if (-not $SkipBuild) {
        Invoke-Check 'Build sample app (debug)' {
            & $Gradlew ':sample:assembleDebug' --quiet 2>&1 | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'Sample build failed' }
        }
    }

    # ── 7. Local Maven publish dry-run ─────────────────────────────────
    if (-not $SkipBuild) {
        Invoke-Check 'Publish to mavenLocal (dry sanity)' {
            & $Gradlew ':editor:publishToMavenLocal' ':languages:publishToMavenLocal' --quiet 2>&1 | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'publishToMavenLocal failed' }
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
