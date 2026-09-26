<!-- Thanks for contributing to Aardink! -->

## Summary

<!-- What does this PR do, and why? Focus on the "why". -->

## Changes

<!-- Bullet list of notable changes. -->

-

## Checklist

- [ ] `./scripts/pre-push.ps1 -NoFix` passes (Spotless, lint, ABI, JVM + wasmJs tests, Vite smoke, sample build, screenshots, consumer smoke)
- [ ] If the public API changed, ran `./gradlew updateAbiAll` and committed the updated `*/api/` dumps
- [ ] The ABI diff is purely additive (no removed or changed signatures before 0.5.0)
- [ ] If the editor renders differently, re-recorded `screenshots/` and explained the visual diff above
- [ ] New source files include the Apache 2.0 header
- [ ] No new runtime dependencies added (or justified in the PR description)
- [ ] New code is in `commonMain`; any `expect`/`actual` is under `platform/`
- [ ] No XML layouts or Android resource files added to the `editor` module

## Related issues

<!-- e.g. Closes #123 -->
