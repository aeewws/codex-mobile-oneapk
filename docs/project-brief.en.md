# Codex Mobile Project Brief

The primary repository experience is Chinese-first. For the main project brief, see [project-brief.md](./project-brief.md).

This page is a public-facing summary of what Codex Mobile is, why it exists, and how to describe it consistently.

## One-Line Summary

Codex Mobile is a mobile-first Android shell for a real local Codex runtime.

## What It Is

Codex Mobile turns a real local Codex runtime into a touch-first Android product instead of a terminal-first workflow.

It is not:

- a fake chat wrapper around a remote model
- a terminal screenshot packaged as an app
- a generic Android AI client with no runtime assumptions

It is:

- a real mobile UI around a local Codex runtime
- a bridge between Android product interaction and app-managed backend execution
- a practical experiment in making local AI coding usable on an actual phone

## Why It Matters

Most local coding-agent workflows are terminal-native and desktop-oriented. That makes them powerful, but hard to use from a real mobile device.

Codex Mobile explores a different layer of the stack:

- mobile thread recovery and reconnect behavior
- product-style session history and lifecycle management
- model, reasoning, and permission controls exposed in a touch-first UI
- Android-specific runtime constraints such as backgrounding, keepalive, and instability handling

## Current Public Signals

- public GitHub repository
- MIT license
- CI running on pull requests and `main`
- maintainer docs such as `SECURITY.md`, `CODEOWNERS`, and Dependabot config
- setup notes and roadmap docs

## What Exists Today

- Android app built with Jetpack Compose
- embedded arm64 runtime packaging and extraction
- app-private `CODEX_HOME`, auth cache, and backend log management
- browser OAuth, device-code login, and local logout
- start/stop/probe/recover control around local `codex app-server`
- history, archive, restore, rename, and delete flows
- model switching, reasoning switching, permission modes, and Fast mode
- document attachment extraction for supported formats
- long-thread recovery work for unstable mobile scenarios

## What Makes The Project Non-Trivial

- it depends on a real local runtime instead of a mocked interface
- Android backgrounding and reconnect behavior are part of the product problem
- the backend lifecycle is part of the UX, not just an install detail
- it has to reconcile product design with rooted-device and local-runtime constraints

## Copyable Blurbs

### Short

Codex Mobile is a mobile-first Android shell for a real local Codex runtime, focused on thread recovery, session lifecycle, and touch-first interaction.

### Medium

Codex Mobile is an Android product shell around a real local Codex runtime managed by the app itself. Instead of treating the terminal as the final interface, it adds a touch-first mobile layer for chat, history, reconnect handling, model controls, and runtime lifecycle management on a real phone.

### Longer

Codex Mobile is an Android app that turns a real local Codex runtime into a usable mobile product. The current rebuild packages an arm64 runtime into the APK, then lets the app manage its private `CODEX_HOME`, authentication state, and `codex app-server` lifecycle directly. The project focuses on the hard parts that appear when a coding-agent workflow leaves the desktop: thread recovery, reconnect handling, local backend lifecycle, mobile-friendly session history, and exposing runtime controls without raw terminal UX. It is not a fake chat wrapper; it is a mobile-first shell around a real local coding runtime.

## Suggested OSS Positioning

If you need to describe the project to reviewers, users, or maintainers, the safest consistent framing is:

1. It is a mobile-first shell around a real local runtime.
2. It is trying to productize local AI coding workflows on Android.
3. The hard problem is not “making a UI”, but handling lifecycle, recovery, and touch-first interaction reliably.

## Recent Repository Progress

Recent repository work mostly improved public maintenance signals:

- Chinese-first README
- Chinese-first documentation entry points
- runtime architecture rebuilt from external Termux dependency to a single-APK embedded runtime
- in-app login, device-code auth, and local logout
- stronger CI workflow coverage
- security and maintainer metadata

## Links

- Setup: [docs/setup.en.md](./setup.en.md)
- Roadmap: [docs/roadmap.en.md](./roadmap.en.md)
