#Requires -Version 7.0
<#
.SYNOPSIS
    Shared functions for the release scripts (update-changelog.ps1, create-release.ps1, release.ps1).
.DESCRIPTION
    Dot-source this file from any sibling release script in scripts/:
        . (Join-Path $PSScriptRoot 'Release-Common.ps1')
    Provides commit classification, version detection from gradle.properties, next-version
    suggestion, and CHANGELOG section helpers.
#>

Set-StrictMode -Version Latest

# --- Classification rules (first match wins) ---
$script:ClassificationRules = @(
    @{ Pattern = '(?i)^feat(\(.+\))?:\s*';        Category = 'Added' }
    @{ Pattern = '(?i)^fix(\(.+\))?:\s*';         Category = 'Fixed' }
    @{ Pattern = '(?i)^refactor(\(.+\))?:\s*';    Category = 'Changed' }
    @{ Pattern = '(?i)^deprecated(\(.+\))?:\s*';  Category = 'Deprecated' }
    @{ Pattern = '(?i)^removed(\(.+\))?:\s*';     Category = 'Removed' }
    @{ Pattern = '(?i)^security(\(.+\))?:\s*';    Category = 'Security' }
    @{ Pattern = '(?i)^Add\s';                    Category = 'Added' }
    @{ Pattern = '(?i)^Implement\s';              Category = 'Added' }
    @{ Pattern = '(?i)^Refactor\s';               Category = 'Changed' }
    @{ Pattern = '(?i)^Update\s';                 Category = 'Changed' }
    @{ Pattern = '(?i)^Enhance\s';                Category = 'Changed' }
    @{ Pattern = '(?i)^Improve\s';                Category = 'Changed' }
    @{ Pattern = '(?i)^Simplify\s';               Category = 'Changed' }
    @{ Pattern = '(?i)^Remov(e|ed)\s';            Category = 'Removed' }
    @{ Pattern = '(?i)^Deprecate\s';              Category = 'Deprecated' }
    @{ Pattern = '(?i)^Fix\s';                    Category = 'Fixed' }
)

function Test-CommitIncluded {
    param([string]$Subject)
    return -not (
        $Subject -match '^(chore|fix)\(deps\)' -or
        $Subject -match '^Merge pull request' -or
        $Subject -match '(?i)^chore(\(.+\))?:' -or
        $Subject -match '(?i)^docs(\(.+\))?:'
    )
}

function ConvertTo-ChangelogEntry {
    param([string]$Subject)

    $isBreaking = $Subject -match '(?i)^[a-z]+(\(.+\))?!:' -or $Subject -match 'BREAKING CHANGE'
    $cleaned = $Subject -replace '(?i)^([a-z]+)(\(.+\))?!:', '$1$2:'

    $category = 'Changed'
    $message = $cleaned
    foreach ($rule in $script:ClassificationRules) {
        if ($cleaned -match $rule.Pattern) {
            $category = $rule.Category
            if ($cleaned -match '(?i)^[a-z]+(\(.+\))?:\s*') {
                $message = $cleaned -replace '(?i)^[a-z]+(\(.+\))?:\s*', ''
            }
            break
        }
    }

    if ($message.Length -gt 0) {
        $message = $message.Substring(0, 1).ToUpper() + $message.Substring(1)
    }
    if ($isBreaking) {
        $message = "**BREAKING** $message"
    }

    return [PSCustomObject]@{
        Category   = $category
        Message    = $message
        IsBreaking = $isBreaking
        IsFeature  = ($category -eq 'Added')
    }
}

function Get-LatestSemverTag {
    $allTags = git tag --sort=-v:refname | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' }
    if ($allTags -and $allTags.Count -gt 0) { return $allTags[0] }
    return $null
}

function Get-CommitsSinceTag {
    param([string]$Tag)
    $range = if ($Tag) { "$Tag..HEAD" } else { 'HEAD' }
    $raw = git log $range --pretty=format:"%s" --no-merges
    if (-not $raw) { return @() }
    return @($raw | Where-Object { Test-CommitIncluded $_ })
}

function Get-CategorizedCommits {
    param([string[]]$Commits)

    $categories = [ordered]@{
        'Added'      = [System.Collections.Generic.List[string]]::new()
        'Changed'    = [System.Collections.Generic.List[string]]::new()
        'Deprecated' = [System.Collections.Generic.List[string]]::new()
        'Removed'    = [System.Collections.Generic.List[string]]::new()
        'Fixed'      = [System.Collections.Generic.List[string]]::new()
        'Security'   = [System.Collections.Generic.List[string]]::new()
    }
    $hasBreaking = $false
    $hasFeature = $false
    $seen = [System.Collections.Generic.HashSet[string]]::new()

    foreach ($subject in $Commits) {
        $r = ConvertTo-ChangelogEntry -Subject $subject
        if ($seen.Add($r.Message)) {
            $categories[$r.Category].Add("- $($r.Message)")
            if ($r.IsBreaking) { $hasBreaking = $true }
            if ($r.IsFeature)  { $hasFeature  = $true }
        }
    }

    return [PSCustomObject]@{
        Categories  = $categories
        HasBreaking = $hasBreaking
        HasFeature  = $hasFeature
    }
}

function Get-SuggestedNextVersion {
    param(
        [string]$CurrentVersion,
        [bool]$HasBreaking,
        [bool]$HasFeature
    )

    $parts = $CurrentVersion.Split('.')
    $major = [int]$parts[0]; $minor = [int]$parts[1]; $patch = [int]$parts[2]

    if ($HasBreaking) {
        return [PSCustomObject]@{ Version = "$($major + 1).0.0";       Reason = 'BREAKING changes detected' }
    } elseif ($HasFeature) {
        return [PSCustomObject]@{ Version = "$major.$($minor + 1).0";  Reason = 'New features detected' }
    } else {
        return [PSCustomObject]@{ Version = "$major.$minor.$($patch + 1)"; Reason = 'Bug fixes / maintenance only' }
    }
}

function Get-VersionFromGradleProperties {
    param([string]$RepoRoot)
    $path = Join-Path $RepoRoot 'gradle.properties'
    $line = Select-String -Path $path -Pattern '^VERSION_NAME=(.+)$' | Select-Object -First 1
    if (-not $line) {
        throw "VERSION_NAME not found in $path"
    }
    return $line.Matches[0].Groups[1].Value.Trim()
}

function Set-VersionInGradleProperties {
    param(
        [string]$RepoRoot,
        [string]$NewVersion
    )
    $path = Join-Path $RepoRoot 'gradle.properties'
    $content = Get-Content $path -Raw
    if ($content -notmatch '(?m)^VERSION_NAME=.+$') {
        throw "VERSION_NAME line not found in $path"
    }
    $updated = $content -replace '(?m)^VERSION_NAME=.+$', "VERSION_NAME=$NewVersion"
    Set-Content -Path $path -Value $updated -NoNewline
}

function Get-ChangelogSection {
    <#
    .SYNOPSIS
        Extract a single ## [version] section from CHANGELOG.md (heading excluded).
    #>
    param(
        [string]$ChangelogPath,
        [string]$Version  # e.g. "0.1.0" or "Unreleased"
    )
    $content = Get-Content $ChangelogPath -Raw
    $escaped = [regex]::Escape($Version)
    $pattern = "(?ms)^## \[$escaped\][^\r\n]*\r?\n(.*?)(?=^## \[|\z)"
    $m = [regex]::Match($content, $pattern)
    if (-not $m.Success) { return $null }
    return $m.Groups[1].Value.Trim()
}
