# npm bootstrap for `@aardarch/aardink-web` (one time, manual)

Written for: the owner of the npm account that will own the `@aardarch` scope. Run these steps
once, in order, on the Windows dev machine (PowerShell 7). Everything after this is automated:
from `v0.6.0-rc1` on, a release tag publishes to npm from `.github/workflows/release.yml`
through OIDC trusted publishing, with no token stored anywhere.

**Why a manual first publish?** npm only lets you attach a trusted publisher to a package that
already exists on the registry ("The package you're configuring must already exist on the npm
registry", `npm trust` docs). So one version goes up by hand, and trusted publishing is
configured on it straight afterwards.

| Step | Where | Takes |
| --- | --- | --- |
| 1. Check tools | terminal | 1 min |
| 2. Create the `aardarch` org | npmjs.com | 2 min |
| 3. Log in | terminal | 1 min |
| 4. Build 0.5.0 from the tag | terminal | about 5 min |
| 5. Check the package | terminal | about 3 min |
| 6. Publish 0.5.0 | terminal | 1 min |
| 7. Add the trusted publisher | terminal or npmjs.com | 2 min |
| 8. Disallow tokens | npmjs.com | 1 min |
| 9. Create the GitHub `npm` environment | github.com or terminal | 2 min |
| 10. Clean up and report back | terminal | 1 min |

## 1. Check tools

```pwsh
node -v    # need 22.14.0 or later (trusted publishing); this machine had v24.21.0
npm -v     # need 11.15.0 or later (for `npm trust`); this machine had 11.19.0
java -version   # JDK 21, as for every Aardink build
```

If npm is older: `npm install -g npm@latest`.

Make sure the npm account has **two-factor authentication enabled** (npmjs.com → avatar →
Account → Two-Factor Authentication). Publishing and the trust setup both ask for it.

## 2. Create the `aardarch` org on npmjs.com

1. Sign in at <https://www.npmjs.com> with the account that owns `@sjohansson`.
2. Open <https://www.npmjs.com/org/create>.
3. **Name:** `aardarch`. This becomes the `@aardarch` scope, and it must be exactly this,
   because the package name `@aardarch/aardink-web` is already in the code, docs and plan.
4. **Plan:** the free plan, "Unlimited public packages".
5. Click **Create**. Skip the "invite members" step for now.

If `aardarch` is taken, **stop here and tell Claude.** The package name then has to change
everywhere before anything is published.

## 3. Log in from the terminal

```pwsh
npm login                      # opens the browser; approve with 2FA
npm whoami                     # prints your npm username
npm org ls aardarch            # lists you, with role "owner"
```

## 4. Build the 0.5.0 package from the `v0.5.0` tag

Use a separate worktree so this doesn't touch the `v0.6-uplift` branch that Claude is working
on in `C:\repos\aardarch\aardink`.

```pwsh
git -C C:\repos\aardarch\aardink fetch --tags
git -C C:\repos\aardarch\aardink worktree add C:\repos\aardarch\aardink-v050 v0.5.0
Copy-Item C:\repos\aardarch\aardink\local.properties C:\repos\aardarch\aardink-v050\
Set-Location C:\repos\aardarch\aardink-v050
.\gradlew.bat :sample-web:npmPackage
```

`local.properties` holds `sdk.dir`: Gradle configures the Android modules even for this
wasm-only task, and `ANDROID_HOME` isn't set on this machine.

The `v0.5.0` package has no README and no LICENSE. npm always includes `README.md` and
`LICENSE` from the package folder, so copy them in:

```pwsh
Copy-Item C:\repos\aardarch\aardink\sample-web\src\npm\README.md sample-web\build\npm\
Copy-Item C:\repos\aardarch\aardink-v050\LICENSE sample-web\build\npm\
```

The README comes from the `v0.6-uplift` branch; it describes the 0.5.x API and known issues
accurately. If that working copy is ever on another branch, take it from the commit instead:

```pwsh
git -C C:\repos\aardarch\aardink show v0.6-uplift:sample-web/src/npm/README.md |
  Set-Content -Encoding utf8NoBOM sample-web\build\npm\README.md
```

## 5. Check the package

```pwsh
Set-Location C:\repos\aardarch\aardink-v050\sample-web\build\npm
Get-Content package.json | Select-String '"name"|"version"'
npm pack --dry-run
```

Expect:

- `"name": "@aardarch/aardink-web"` and `"version": "0.5.0"`. If the version isn't `0.5.0`, the
  worktree isn't on the tag; stop.
- The file list includes `README.md`, `LICENSE`, `JETBRAINS_MONO_OFL.txt`, `index.js`,
  `index.d.ts`, `kotlin/aardink-web.mjs`, `kotlin/aardink-web.wasm`, `kotlin/skiko.mjs`,
  `kotlin/skiko.wasm`, and `kotlin/composeResources/.../jetbrains_mono_regular.ttf`.
- `repository.url` in `package.json` is exactly `git+https://github.com/aardarch/aardink.git`.
  Trusted publishing later checks it against the GitHub repo.

Optional, but it proves this exact build works in a real Vite app (needs Chrome, as for
`pre-push.ps1`):

```pwsh
Set-Location C:\repos\aardarch\aardink-v050\tools\vite-smoke
pnpm install --force --frozen-lockfile
pnpm smoke     # ends with "Vite smoke test passed"
```

## 6. Publish 0.5.0

```pwsh
Set-Location C:\repos\aardarch\aardink-v050\sample-web\build\npm
npm publish --access public    # approve with 2FA when asked
```

`--access public` is required because the `v0.5.0` `package.json` has no `publishConfig`
(scoped packages default to private). The version goes to the `latest` dist-tag.

Check it:

```pwsh
npm view @aardarch/aardink-web version dist-tags
```

Expect `version = '0.5.0'` and `dist-tags = { latest: '0.5.0' }`. The page is
<https://www.npmjs.com/package/@aardarch/aardink-web>.

## 7. Add the trusted publisher

**Terminal (recommended, one line):**

```pwsh
npm trust github @aardarch/aardink-web --file release.yml --repo aardarch/aardink --env npm --allow-publish --allow-stage-publish
npm trust list @aardarch/aardink-web
```

**Or on the website:** npmjs.com → Packages → `@aardarch/aardink-web` → **Settings** →
**Trusted publishing** → **GitHub Actions**, then:

| Field | Value |
| --- | --- |
| Organization or user | `aardarch` |
| Repository | `aardink` |
| Workflow filename | `release.yml` (filename only, no path) |
| Environment name | `npm` |
| Allowed actions | tick **npm publish** (npm stage publish is always allowed) |

**Tick "npm publish"; don't skip it.** Trusted-publisher configurations created since
2026-09-03 allow only staged publishing unless direct publishing is ticked, and the release
workflow publishes directly by default. The stricter staged mode is described at the end.

## 8. Disallow tokens

npmjs.com → Packages → `@aardarch/aardink-web` → **Settings** → **Publishing access** →
select **"Require two-factor authentication and disallow tokens"** → **Update package
settings**.

From now on only the GitHub workflow (through OIDC) or a person with 2FA can publish. Also run
`npm token list` and revoke any old publish tokens you no longer need with
`npm token revoke <id>`.

## 9. Create the `npm` environment on GitHub

The release workflow's npm job runs in the environment `npm`. The trusted publisher only
accepts tokens from that environment, and the environment only accepts `v*` tags.

**On the website:** <https://github.com/aardarch/aardink/settings/environments> →
**New environment** → name `npm` → **Configure environment**:

1. **Deployment branches and tags:** choose **Selected branches and tags** → **Add deployment
   branch or tag rule** → Ref type **Tag** → Name pattern `v*` → **Add rule**.
2. Optional, **Required reviewers:** add yourself if every npm publish should wait for your
   click. Maven Central does not wait, so leave it off to keep both registries in step.
3. **Save protection rules.** No secrets or variables are needed.

**Or with the GitHub CLI:**

```pwsh
gh api -X PUT repos/aardarch/aardink/environments/npm --input - <<'JSON'
{"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
JSON
gh api -X POST repos/aardarch/aardink/environments/npm/deployment-branch-policies -f name='v*' -f type=tag
gh api repos/aardarch/aardink/environments/npm/deployment-branch-policies
```

PowerShell has no `<<` heredoc; in PowerShell use this instead of the first command:

```pwsh
'{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' |
  gh api -X PUT repos/aardarch/aardink/environments/npm --input -
```

## 10. Clean up and report back

```pwsh
Set-Location C:\repos\aardarch
git -C C:\repos\aardarch\aardink worktree remove --force C:\repos\aardarch\aardink-v050
```

Then tell Claude it's done, with the output of:

```pwsh
npm view @aardarch/aardink-web dist-tags
npm trust list @aardarch/aardink-web
gh api repos/aardarch/aardink/environments/npm --jq '.name, .deployment_branch_policy'
```

The first automated publish is `v0.6.0-rc1`, which goes to the `next` dist-tag with a
provenance badge; `latest` stays on 0.5.0 until `v0.6.0`.

## Optional: staged publishing (a 2FA click per release)

For a manual gate on npm only, set the repository variable `NPM_PUBLISH_MODE` to `stage`
(<https://github.com/aardarch/aardink/settings/variables/actions> → **New repository
variable**). The workflow then runs `npm stage publish` instead of `npm publish`, and nothing
becomes public until a maintainer approves it:

```pwsh
npm stage list @aardarch/aardink-web
npm stage approve <stage-id>    # asks for 2FA
```

With that mode you can untick "npm publish" in step 7, so a compromised workflow can only
stage, never publish.

## If something fails

| Symptom | Cause and fix |
| --- | --- |
| `npm publish` returns 402 Payment Required | The scoped package defaulted to private. Pass `--access public`. |
| `npm publish` returns 403 | Not logged in as an owner of `@aardarch` (`npm org ls aardarch`), or 2FA not approved. |
| The npm job fails with `ENEEDAUTH` or a 404 on `PUT` | The trusted publisher doesn't match: check the org, repo, `release.yml` and environment `npm` exactly, and that `id-token: write` is in the job. |
| The npm job's `npm publish` is rejected, although OIDC login worked | "npm publish" wasn't ticked in step 7, so only staging is allowed. Tick it, or switch to staged mode. |
| The npm job waits for or fails on the environment | The `v*` tag rule is missing in step 9. |
| Provenance is rejected | `repository.url` doesn't exactly match `git+https://github.com/aardarch/aardink.git`. |
