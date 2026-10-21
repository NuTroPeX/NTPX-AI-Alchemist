# NTPX AI Alchemist — Architecture (v0.2)

Standalone, Android-first AI orchestration platform. Kept independent of Genesis Nexus and the
NTPX-Personal-AGI-ASI master repo until the release gates below pass.

## Module graph

```
:app  ──►  :core:agent-runtime   (pure Kotlin/JVM)
  │   ──►  :core:model-registry  (pure Kotlin/JVM)
  │   ──►  :core:mcp             (pure Kotlin/JVM)
  └──────► :core:memory          (Android library, Room)
```

The three JVM modules have no Android dependency, so they test fast on any JVM and can be reused
by the Windows phase later. `:core:agent-runtime` depends on none of the others: it talks to models,
tools and memory through **ports** (`ModelPort`, `ToolPort`, `MemoryPort`) that `:app` implements
in `data/Adapters.kt`.

## Modules

### `:core:model-registry`
- `ModelDescriptor` — id, provider, placement (`on_device` / `local_network` / `cloud`),
  capabilities, context window, size, backend id, availability.
- `ModelRegistry` / `InMemoryModelRegistry` — observable (`StateFlow`) registry with queries.
- `ModelCatalogParser` — versioned JSON catalog (`app/src/main/assets/models.json`), rejects
  duplicates, unknown schema versions and invalid entries.
- `ModelRouter` + `RoutingPolicy` — picks a **ready** model whose backend is registered;
  prefers on-device; cloud is excluded unless explicitly allowed.
- `ModelBackend` — inference plug-in point. v0.2 ships only `EchoBackend`, a deterministic
  backend that reflects the prompt so the whole pipeline can be verified. **It performs no inference.**

### `:core:agent-runtime`
- `AgentTask` / `AgentStep` (`Generate`, `CallTool`, `Recall`, `Remember`) with `{{goal}}`,
  `{{last}}`, `{{step:N}}` templates.
- `PermissionGate` + `GrantTable` — per-agent `ALLOW` / `ASK` / `DENY`; anything unset is
  `DENY` (fail closed). `ASK` goes to a `ConsentProvider` (the app's dialog).
- `AgentRuntime.run(task): Flow<TaskEvent>` — checks every permission a step needs before running
  it, enforces a step budget and per-step timeout, stops on the first denial/error, and audits
  every transition. Cancelling the collector cancels the task and is audited.
- `AuditLog` / `InMemoryAuditLog` — append-only, SHA-256 hash-chained with length-prefixed
  fields; editing or removing an entry breaks verification from that point on.
- `RecallGenerateRememberPlanner` — fixed four-step plan used by the Agents tab.

### `:core:mcp`
- JSON-RPC 2.0 codec with MCP error codes.
- `McpServer` — in-process server for `initialize`, `notifications/initialized`, `ping`,
  `tools/list`, `tools/call`; validates required and unknown arguments; tool exceptions are
  returned in-band as `isError` results.
- `McpClient` + `McpTransport` (`LoopbackTransport` today; network transports plug in here).
- Tools declare extra permissions in `_meta["ntpx/permissions"]`; the app maps them to runtime
  permissions, and an unknown permission name fails the step.
- Built-in offline tools: `text.stats`, `clock.now`, `math.evaluate` (recursive-descent
  BigDecimal parser with length, depth and exponent limits; never executes code).

### `:core:memory`
- `MemoryStore` with `InMemoryMemoryStore` and `RoomMemoryStore` (schema exported to
  `core/memory/schemas/`).
- `MemoryRanker` — BM25-style lexical ranking with tag, phrase and pin boosts. An
  embeddings ranker can replace it once an on-device embeddings model is available.
- `MemoryExportCodec` — versioned JSON export/import so the user owns the data.

### `:app`
- Jetpack Compose + Material 3, neon-noir theme (Rajdhani, JetBrains Mono; OFL licences in
  `docs/licenses/`).
- Tabs: **Registry** (filter by capability/placement/readiness), **Agents** (run a goal, live step
  timeline, loaded MCP tools), **Memory** (add, search, pin, delete, export via share sheet,
  import from file, clear with confirmation), **Security** (routing toggles, per-permission grants,
  audit log with live chain verification).
- `AppContainer` — manual DI. Settings and grants persist in private SharedPreferences; the audit
  chain persists as JSON lines in private storage and is re-verified at startup.

## Security posture (v0.2)
- **No `INTERNET` permission.** The app cannot reach the network; cloud and LAN models are listed
  but not routable.
- Backup and device transfer are disabled for all app data.
- Cloud routing needs three things at once: the Settings toggle, the `INVOKE_CLOUD_MODEL`
  permission and the `NETWORK` permission.
- Consent prompts that go unanswered for 60 s are treated as **deny**.

## Release gates
1. CI green: unit tests in every module, lint, debug and release (R8) builds.
2. Install the debug APK on a physical Android device and launch it.
3. Walk the core flows by hand: registry filters, an agent run with consent prompts (allow and
   deny), memory add/search/export/import/clear, permission changes, and audit chain shown as verified
   after an app restart.

CI success does not establish model inference, autonomous execution, or a signed release.

## Next milestones
- Real on-device inference backend (llama.cpp JNI or MediaPipe LLM) registered against the catalog.
- Model download manager with checksums, storage accounting and `Availability` updates.
- Embeddings-backed memory ranker.
- Streamable HTTP MCP transport and the `NETWORK` permission, behind the cloud opt-in.
- Encrypted-at-rest memory (SQLCipher or Android Keystore-wrapped keys).
