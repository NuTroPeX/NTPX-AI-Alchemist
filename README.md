# NTPX-AI-Alchemist

Android-first AI orchestration platform: a model registry, a permission-gated agent runtime, MCP
tools and user-owned local memory. It runs offline-first, and cloud models are always opt-in.

**Status: v0.2 foundation.** Everything is wired end to end and covered by tests. The only bundled
model backend is a deterministic echo used to verify the pipeline. Real on-device inference is the
next milestone. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## What works today
- **Registry.** A catalog of on-device, local-network and cloud models, filterable by capability, placement and readiness.
- **Agents.** Run a goal through recall → generate → tool → remember, with a live step timeline.
- **Permissions.** Per-agent allow, ask or deny for every capability, with consent dialogs. The runtime fails closed.
- **Audit.** A SHA-256 hash-chained log, persisted on device and re-verified at startup.
- **MCP.** An in-process MCP server and client that ship with `text.stats`, `clock.now` and `math.evaluate`.
- **Memory.** Room storage with ranked search, pinning, JSON export and import, and clear-all.
- **Offline.** The app requests no network permission, and backup and device transfer are disabled.

## Build

Requirements: JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0).

```bash
./gradlew test                 # unit tests, all modules
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug
```

CI (`.github/workflows/android.yml`) runs the tests, lint, and the debug and release builds on every
push to `main` and on every pull request. The debug APK is uploaded as an artifact.

## Layout

| Module | Kind | Contents |
|---|---|---|
| `:app` | Android app | Compose UI, DI, adapters, settings, audit journal |
| `:core:model-registry` | Kotlin/JVM | Model descriptors, catalog parser, router, backends |
| `:core:agent-runtime` | Kotlin/JVM | Tasks, permissions, consent, audit chain, runtime |
| `:core:mcp` | Kotlin/JVM | JSON-RPC/MCP codec, server, client, built-in tools |
| `:core:memory` | Android library | Memory store (Room + in-memory), ranker, export |
