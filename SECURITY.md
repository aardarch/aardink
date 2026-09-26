# Security Policy

## Supported versions

Aardink is pre-1.0. Only the latest published version on Maven Central
receives fixes.

| Version | Supported |
| ------- | --------- |
| latest  | yes       |
| older   | no        |

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security problems.

Email **<security@aardarch.com>** with:

- a description of the issue,
- steps to reproduce (or a proof of concept),
- the version / commit affected,
- any suggested mitigations you have in mind.

You can expect an acknowledgement within a few business days. We will work
with you on a coordinated disclosure timeline once the issue is confirmed.

## Scope

In scope: the libraries published on Maven Central (`com.aardarch:aardink`,
`aardink-languages`, `aardink-languages-lsp`, `aardink-editor-web`), the
`@aardarch/aardink-web` npm package built from `sample-web/`, and the sources in
this repository that produce them.

Out of scope: the `sample/`, `sample-desktop/` and `tools/` modules (development-only;
`sample-web/`'s harness page included), third-party
dependencies (please report upstream), and issues that require physical
access to a user's device or already-rooted devices.
