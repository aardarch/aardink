# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

<!-- Release workflow:
  Day-to-day:
    Add entries under [Unreleased] in your PR. You can run ./update-changelog.ps1
    on main to auto-classify commits since the last tag into the right sections.

  Cutting a release:
    1. ./create-release.ps1            # bumps gradle.properties VERSION_NAME,
                                       # cuts [Unreleased] -> [X.Y.Z] - YYYY-MM-DD,
                                       # commits, and creates an annotated tag.
    2. git show vX.Y.Z                 # review the staged commit and tag.
    3. ./release.ps1                   # pushes main + tag, triggering CI publish.

  CI (.github/workflows/release.yml) verifies the tag matches VERSION_NAME, publishes
  to Maven Central, then extracts this matching ## [X.Y.Z] section to create the
  GitHub Release. It will fail if no matching section exists. -->

<!-- Commit prefix → Changelog heading (1:1 mapping)

  feat:        → Added        New features
  refactor:    → Changed      Changes to existing functionality
  deprecated:  → Deprecated   Soon-to-be removed features
  removed:     → Removed      Now removed features
  fix:         → Fixed        Bug fixes
  security:    → Security     Vulnerability fixes
  chore:       → (omitted)    Maintenance, tooling, config
  docs:        → (omitted)    Documentation-only changes

  Append ! for breaking changes: feat!:, fix!:, removed!: etc. -->

## [Unreleased]

### Added

- Show find and replace functionality in sample editor screen
- Add dokka markdown output

### Changed

- Simplify the sample ui logo/mascot placeholder
- Fix aardink namespace typo

## [0.1.0] - 2026-05-12

### Added

- Initial version 0.1.0 of Aardink
