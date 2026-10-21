# NTPX AI Alchemist — Foundation

Standalone Android-first AI orchestration prototype. Keep independent of Genesis Nexa until release gates pass.

## Modules
- Android application: native Kotlin UI, offline-first local controls
- Model registry: metadata and capability discovery (future)
- Agent runtime: explicit permission gates, auditable task execution (future)
- MCP/plugin adapters: sandboxed integrations (future)
- Memory: user-controlled local persistence (future)
- Optional cloud models: opt-in authenticated adapters (future)

## Release gates
Build debug APK in CI; install and launch on physical Android; verify core workflows before release claims. CI build success does not establish model inference, autonomous execution, or an installable signed release.
