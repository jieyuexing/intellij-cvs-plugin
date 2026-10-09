# Platform target: IntelliJ IDEA 2026.2.1

Status: **`compileJava` green + `buildPlugin` green** on IDEA 2026.2.1 (2026-08-01).

## Nailed configuration

| Item | Value |
| --- | --- |
| IDE | IntelliJ IDEA **2026.2.1** (IU) |
| Build | `262.9437.22` (local product-info) |
| Plugin `since` / `until` | `232` / `262.*` |
| Gradle | 9.0.0 wrapper + IntelliJ Platform Gradle Plugin **2.18.1** |
| Compile JDK | **25** (use IDEA JBR: `…/IntelliJ IDEA.app/Contents/jbr/Contents/Home`) |
| Platform dependency | Prefer **local** IDE install matching `2026.2`; remote `create(IU, 2026.2.1)` may fail URL resolution |

## How to build

```bash
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
# or: /Applications/... if that path is 2026.2
cd /path/to/intellij-cvs-plugin
./gradlew compileJava
# later:
./gradlew buildPlugin
```

## What was fixed to go green (minimal diffs)

| Theme | Approach |
| --- | --- |
| Missing CP (`vcs.impl`, microba) | `bundledModule("intellij.platform.vcs.impl"…)` + `bundledLibrary(microba)` |
| `FileLabel` / `EditorAdapter` removed | Local shims under `com.intellij.util.ui.*` |
| `FileSystemTreeFactory` removed | `new FileSystemTreeImpl(...)` |
| `VcsVirtualFile` ctor | `VcsVirtualFile(FilePath, VcsFileRevision)` |
| `ChangelistBuilder` | `processUnversioned/Ignored(FilePath)` via `VcsUtil.getFilePath` |
| `VcsConfiguration.getCheckoutOption` gone | `PerformInBackgroundOption.DEAF` |
| Annotations / icons / lazy | Drop `CalledInBackground`; replace Cvs icons; `NotNullLazyValue.atomicLazy` |
| Dead `@Override`s on AbstractVcs | Remove override where method removed (`getMenuItemText`, `isVersionedDirectory`, `getCheckoutProvider`, …) |

**Known behavioral gaps (compile OK, runtime TBD):**

- SOCKS host-specific `CommonProxy.setCustom(ProxySelector)` removed — only auth registration kept.
- File chooser toolbar no longer injects default platform tree actions.
- Background-option / add-remove confirmation wiring simplified to `DEAF`.

## Build artifact

```bash
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
python3 scripts/check_i18n_keys.py
python3 scripts/check_rust_roadmap.py
./gradlew buildPlugin
# -> build/distributions/intellij-cvs-plugin-262.0.zip
```

**Version:** `262.x` = IDEA **2026.2** platform line (same scheme as official `223.0` for 2022.3).  
**262.0 is the initial community release.** All items below were completed before the first public release:

- Add zh ResourceBundles.

- Set `since-build=232` / `until-build=262.*` and emit **Java 17** class files so IDEA **2023.2.8** (`IU-232.*`) can install and load the same zip (compile still uses 2026.2 APIs + JDK 25 to *read* platform jars).

- Optimize repository/status scans and error handling. Rust is not part of the runtime; its benchmark-gated future path is documented in [rust-performance-roadmap.md](rust-performance-roadmap.md).

- Restore nested working-copy discovery for container-level CVS mappings on IDEA 232 without reopening recursive scans of generated directories inside a real working copy.

- Add an on-disk fallback when IDEA 232's initial VFS snapshot does not contain ignored `CVS` admin directories, removing the manual “Reload from Disk” prerequisite.

- Add an opt-in SHA-256 baseline under the IDE system cache for copied working copies whose timestamps no longer match `CVS/Entries`. Status refresh remains offline. Repository-backed `CVS/BaseRevisions` has precedence, so a trusted snapshot cannot mask a locally provable change. The baseline is created or removed from the CVS global menu and never mutates the working copy.

- Harden rollback: byte-identical cached restores do not rewrite the working file, and cache/entry/permission failures propagate through the platform rollback error list. IDEA 232 makes its generic rollback progress non-cancelable, so repository-backed restores are queued into a plugin-owned cancellable background task. Files are grouped by exact `CVS/Entries` revision and sent through batched clean updates instead of two checkout protocol conversations per file. Cancel still runs partial-result cleanup; Entries are reconciled once per directory and VFS parent refreshes are deduplicated. The observed IDEA 232 macOS `Too many nested CFRunLoopRuns` crash is JetBrains Runtime issue [JBR-7659](https://youtrack.jetbrains.com/issue/JBR-7659), fixed in `jbr21.895.105`; the official workaround for users who do not require VoiceOver is `-Dsun.awt.mac.a11y.enabled=false`.

- Make mapped containers discovery-only: a cancellable background scan reads CVS admin markers from disk, publishes only complete root snapshots, refreshes the discovered VFS paths, and triggers the initial dirty scan. ChangeProvider drops dirty paths outside confirmed roots, so generated/runtime siblings are not reported as unversioned.

- Handle IDEA 232's dirty-scope root semantics: a recursively dirty directory is now accepted when it is the explicit scope root, even though `VcsDirtyScope.belongsTo(root)` only recognizes descendants. This lets the first scan enter all discovered working copies without a manual child-directory refresh.

- Extend that compatibility to the full recursive dirty tree. Once an explicit recursive root is accepted, its descendants are processed without repeating IDEA 232's unreliable converted-root membership check, so nested source changes are present on the first scan.

- Anchor the post-discovery dirty scope at each configured mapping container instead of calling `markEverythingDirty()` on converted roots. IDEA 232's `ChangelistBuilder` now accepts the provider output from nested sibling CVS roots and publishes it to the Changes view.

- Add a fail-closed repository verification for timestamp-only false positives. The explicit action runs `cvs -n update` through the existing IDEA connection, captures every server-reported path, and atomically caches only silent candidates whose working metadata and `CVS/Entries` stayed stable. Cancellation or any repository warning/error leaves the prior cache unchanged.

- De-duplicate overlapping recursive dirty scopes before traversal. This prevents mapped containers, converted CVS roots, and explicit descendants from submitting equal `Change` objects more than once in the same refresh.

- Index server-reported paths and check only each candidate's ancestors. This removes the quadratic candidate-by-report comparison from large repository-verification runs.

### i18n (en + zh)

| File | Role |
| --- | --- |
| `CvsBundle.properties` | English baseline |
| `CvsBundle_zh.properties` | Chinese — auto when IDEA UI locale is zh |
| `JavaCvsSrcBundle_zh.properties` / `SmartCvsSrcBundle_zh.properties` | Secondary bundles |

No manual language switch in the plugin: IntelliJ `DynamicBundle` follows IDE display language.

### Install smoke on 2026.2.1

1. IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk…  
2. Choose `build/distributions/intellij-cvs-plugin-262.0.zip`
3. Restart; confirm **OpenCVS** (id `io.github.jieyuexing.cvs`, version **262.0**).
4. With **Chinese** UI language: Settings → Version Control → CVS / Global Settings should show Chinese labels.  
5. Optional: open a CVS working copy; try Browse / Checkout / Update / History.

### Rollback release gate

Before publishing 262.0, test the same multi-file selection on IDEA 2023.2.8 and 2026.2.1:

1. Roll back files with and without `CVS/BaseRevisions`; cached files should finish locally.
2. Confirm repository-backed files appear in a separate **Restore** background task with a visible Cancel action.
3. Cancel mid-run; IDEA must remain responsive and a subsequent refresh must show only files that were not restored.
4. Complete the run; confirm every file stays on its original `CVS/Entries` revision and no `.#file.revision` siblings remain.

## Next steps

1. Manual install smoke on **2026.2.1** (checklist above).
2. Complete the rollback release gate on **2023.2.8** and **2026.2.1**.
3. Runtime fixes for SOCKS / chooser / confirmation if needed.
4. Run Rust roadmap Phase 0 profiling only after the Java path is stable; do not add Cargo/native code before the go/no-go gate.


## Marketplace preparation: 262.0.2 (2026-10-09)

`verifyPlugin` (Verifier 1.410) completed both targets: downloaded IDEA 2023.2.8
(IU-232.10335.12) and the current local IDEA 2026.2 (IU-262.10968.63).
It exited 1 due to `INTERNAL_API_USAGES`; this is not a dependency-resolution failure.

| Category | 232 | 262 |
| --- | ---: | ---: |
| Compatibility problems reported | 0 | 0 |
| Compatibility warnings | 1 | 1 |
| Internal API usages | 0 | 32 |
| Scheduled-for-removal usages | 65 | 126 |
| Deprecated usages | 98 | 139 |
| Experimental usages reported | 0 | 0 |

Both warnings concern the inherited `com.intellij.cvsSupport2` package. The 262
internal usages involve `CharsetToolkit`, `ActionsBundle`/`IdeBundle`, `SLRUCache`,
`FileChooserFactoryImpl.getMacroMap`, `AbstractVcs` root conversion/filtering,
and `ChangesUtil.findValidParentAccurately`. They span content decoding, settings,
checkout, chooser UI and status-root semantics. Replacing these is not a mechanical
rename: BOM/encoding handling and the existing 232 root-discovery fixes must remain
behaviorally equivalent. No Java/protocol changes were made in this release.

Rough follow-up estimate (not an implementation commitment): 1–2 days to investigate
public alternatives and fixture boundaries, 3–5 days for focused adapters and dual-IDE
checks, plus 2–3 days of real CVS workflow regression. Package migration and the wider
deprecation backlog require separate scoping. Internal API use and the OpenBSD name
collision remain Marketplace approval risks; these reports do not prove runtime
checkout/update/commit/rollback behavior. Verifier also printed missing layout paths
for the local modular IDE; both verdicts completed, but this diagnostic should be
retained when interpreting the coverage.
