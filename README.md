# intellij-cvs-plugin

**English** | [中文](README_ZH.md)

Community fork of the IntelliJ **CVS** integration plugin, maintained by **jieyuexing**.

**Not affiliated with JetBrains.**

## Fork of

Upstream (original JetBrains source):

https://github.com/JetBrains/intellij-obsolete-plugins/tree/master/cvs

Official Marketplace listing (frozen):

https://plugins.jetbrains.com/plugin/10746-cvs

This repository is an independent maintenance fork of the obsolete-plugins tree (Apache-2.0).  
License: [Apache License 2.0](LICENSE)

## Why this fork

JetBrains deprecated CVS support, moved the code to `intellij-obsolete-plugins`, and stopped advancing the Marketplace build. The **official plugin will not keep pace with newer IntelliJ platforms**. This project restarts community maintenance under a new plugin id so users on newer IDEs can keep using CVS.

## Official plugin status (JetBrains)

| Item | Value |
| --- | --- |
| Marketplace | [CVS · plugin 10746](https://plugins.jetbrains.com/plugin/10746-cvs) |
| Plugin id | `CVS` |
| **Last release** | **`223.0`** (Marketplace update id 268690) |
| **Compatible range** | **build `203.1` — `221.*`** |
| **IntelliJ IDEA** | **`2020.3` — `2022.1.4`** |
| Approx. release time | 2022-12 (Marketplace `cdate`) |
| Declared posture | Deprecated / provided as-is; source on obsolete-plugins |

**Interpretation:** the official line is effectively **frozen at 223.0**. It does **not** cover IDEA 2022.2+ (build `222.*` and later). New work happens only in this community fork (or other forks), not on plugin 10746.

## Timeline

| When | What |
| --- | --- |
| Pre-2019 | CVS lived in the main IntelliJ IDEA source tree and shipped with the IDE. |
| 2019.2+ | Many rarely used plugins (including CVS) moved out of the main repo into [intellij-obsolete-plugins](https://github.com/JetBrains/intellij-obsolete-plugins). |
| 2020-11 | JetBrains published [CVS integration deprecation](https://blog.jetbrains.com/idea/2020/11/cvs-integration-deprecation); CVS is no longer a product priority. |
| ~2020.3 – 2022.1 | Official Marketplace builds still install on IDEA in this window (`since`/`until` on 223.0). |
| **2022-12** | Official Marketplace **`223.0`** published — **last known JetBrains line**. Compatibility capped at **`221.*` / IDEA 2022.1.4**. |
| 2023+ | Platform moves on (`222`, `223`, `231`, …). Official CVS stays at 223.0; newer IDEs cannot use it within declared compatibility. |
| **2026-07** | This fork starts: source taken from obsolete-plugins/cvs, new id `io.github.jieyuexing.cvs`, vendor `jieyuexing`, version **`262.0`** aligned with IDEA **2026.2** (platform `262`). |

```text
  IDE bundled / main tree          obsolete-plugins + Marketplace
  ─────────────────────►│◄──────────────────────────────────────
                        │
                     2019.2+
                        │
                 deprecation blog (2020-11)
                        │
              official 223.0 freezes (≈2022-12)
              203.1 ──────── 221.* / 2022.1.4
                        │
                        ▼
              community fork restarts (2026-07 →)
              only IDE versions the maintainer uses (early phase)
```

## Maintenance policy (early phase)

This is a **restart**, not a promise of full multi-version support.

| Rule | Meaning |
| --- | --- |
| Official line is historical | Do not expect JetBrains to ship past 223.0 / 2022.1.4. |
| New id; version follows IDEA train | Plugin id is `io.github.jieyuexing.cvs`. **Version major matches IDEA platform line** (official last `223.0` for 2022.3; this fork **`262.0`** for 2026.2). |
| **Maintainer-first targets** | Early on, **only IntelliJ / platform versions that the maintainer actually uses** will be prioritized for build, smoke test, and fix. |
| Others welcome but not guaranteed | Issues and PRs for other IDE versions are welcome; they may wait until the maintainer can run that version, or until a contributor owns the verification. |
| No “supports all latest IDEA” claim | Compatibility is whatever is currently listed in `sinceBuild` / `untilBuild` and the table below — nothing more. |

### Target IDE versions (living table)

Update this table when a version is adopted or dropped.  
Maintainer machines currently run the two **Active** rows only.

| Priority | IDE / platform | Approx. build line | Status | Notes |
| --- | --- | --- | --- | --- |
| Baseline (upstream last) | IDEA `2020.3` – `2022.1.4` | `203` – `221` | Historical (official 223.0) | Reference only; not the fork’s main track |
| Source tree default | obsolete-plugins fragment | `2022.3` / `223` | Unverified | Gradle still incomplete; not a maintainer install target |
| **Active (primary)** | **IntelliJ IDEA `2026.2.1`** | **`262.*`** | **Supported** | Compile classpath; install range includes 262 |
| **Active (secondary)** | **IntelliJ IDEA `2023.2.8`** | **`232.*`** | **Supported (install)** | `sinceBuild=232`; Java 17 bytecode so IU-232 can load |
| Out of scope for now | Other IDE versions | — | Best-effort / later | No commitment until added as Active |

**Early build strategy**

1. ~~Standalone Gradle for **2026.2.1**~~ — **done** (`build.gradle.kts` + wrapper; see [docs/platform-2026.2-notes.md](docs/platform-2026.2-notes.md)).
2. Make `compileJava` / `buildPlugin` green on **2026.2.1** (API adapters; minimal diffs).
3. Install smoke on **2026.2.1**, then **2023.2.8**.
4. Widen `since`/`until` only after both Active targets work. Official `223.0` range stays historical.

## Identity (maintainer = jieyuexing)

| Field | Value |
| --- | --- |
| Plugin ID | `io.github.jieyuexing.cvs` |
| Display name | CVS (Community) |
| Vendor | `jieyuexing` |
| Vendor URL | https://github.com/jieyuexing |
| Group | `io.github.jieyuexing` |
| Upstream plugin id (JetBrains) | `CVS` |
| Upstream Marketplace | [10746-cvs](https://plugins.jetbrains.com/plugin/10746-cvs) last **223.0** |

This **plugin id differs** from the original JetBrains id (`CVS`), so it will not replace or conflict with the official Marketplace listing. Uninstall the official CVS plugin if both would be installed.

## Agent / contributor contract

See **[AGENTS.md](AGENTS.md)** for working rules:

1. **Keep original code** by default (minimal diffs; preserve CVS protocol & package layout).
2. **Decouple and enhance** on top (build, platform adapt, testability) — no big-bang rewrites.
3. **i18n for user-facing labels**: English baseline + Chinese (`en` + `zh`) for now.

Human-facing docs:

| Language | File |
| --- | --- |
| English | [README.md](README.md) (this file) |
| 中文 | [README_ZH.md](README_ZH.md) |

## Status

Early fork setup:

- [x] Source copied from archive
- [x] Fork identity (id / vendor / description)
- [x] Document official freeze (223.0 / 2020.3–2022.1.4) and restart timeline
- [x] `AGENTS.md` (preserve code / decouple / en+zh labels)
- [x] Maintainer targets: IDEA **2026.2.1** (primary), **2023.2.8** (secondary)
- [x] Chinese README (`README_ZH.md`)
- [x] Standalone Gradle (Platform Plugin 2.18.1, Java 25, `since`/`until` = `232` / `262.*`)
- [x] `compileJava` green on **2026.2.1**
- [x] `buildPlugin` green (initial release version **262.0**: since **232** … until **262.***, Java 17 classes, zh bundles)
- [x] `CvsBundle_zh` + sibling bundles; IDEA locale auto-switch via `DynamicBundle`
- [x] Task mechanism: [docs/task-mechanism.md](docs/task-mechanism.md) + harness Task `task-intellij-cvs-i18n-zh-v1`
- [x] Rust performance [roadmap](docs/rust-performance-roadmap.md) recorded as planning-only and benchmark-gated
- [ ] Rust Phase 0 Java baseline/profiling and explicit go/no-go decision
- [ ] Install smoke on **2026.2.1** (install the 262.0 release-candidate zip under Chinese UI)
- [x] Install/root-discovery smoke on **2023.2.8**
- [ ] Large-directory rollback smoke on **2023.2.8** and **2026.2.1**: verify batched exact-revision restore, visible Cancel, partial-cancel recovery, and no `.#file.revision` leftovers
- [ ] Optional Marketplace (community) publish

## Build (2026.2.1)

```bash
# Use JBR 25 from IDEA 2026.2.1
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
python3 scripts/check_i18n_keys.py
python3 scripts/check_rust_roadmap.py
./gradlew buildPlugin
# artifact: build/distributions/intellij-cvs-plugin-262.0.1.zip
```

**Versioning:** major tracks IDEA **2026.2** train (`262.x`).  
**Install range:** `since-build=232` … `until-build=262.*` (IDEA **2023.2** through **2026.2**).  
**262.0 is the initial community release.** All changes below were developed before that first public release:

- Widen the range for IU-232 and emit Java 17 class files (avoids “needs 262” / class-version rejection on 2023.2.8).

- Optimize repository/status scans and error handling; Rust remains a planning-only, optional future fast path.

- Fix IDEA 232 status refresh when one CVS mapping is a container for multiple nested working copies; container paths are no longer shown as a single unversioned directory.

- Discover CVS roots from disk when IDEA 232 has not yet populated ignored `CVS` admin directories in the VFS; initial status no longer requires “Reload from Disk”.

- Add an explicit offline content baseline for copied or extracted CVS working copies whose timestamps were rewritten. It avoids per-file repository access without silently treating a heuristic as authoritative.

- Make cached rollback a no-op when the working file already has identical bytes and surface cache/`CVS/Entries`/permission failures through IDEA's rollback error list. When local base content is unavailable, leave IDEA 232's non-cancelable generic rollback section immediately, then restore files in a plugin-owned cancellable background task. Files are grouped by exact `CVS/Entries` revision and restored with batched clean updates instead of a checkout connection loop per file. Cancel also runs partial-result cleanup; original Entries are reconciled once per directory, and VFS parent refreshes are deduplicated.

- Separate mapped-container discovery from CVS status ownership. A cancellable startup task discovers working-copy roots directly from disk, refreshes only those VFS paths, and triggers the first Changes scan; paths outside confirmed roots are no longer reported as unversioned.

- Accept an explicitly dirty working-copy root as part of its own IDEA 232 dirty scope. This closes the cold-start gap where all discovered roots were skipped until a descendant was refreshed manually.

- Treat an explicitly recursive dirty root as authorization for its complete subtree. IDEA 232 does not reliably report scope membership for descendants of roots produced by the custom converter; re-checking each child previously stopped initial status collection at the first directory level.

- Dirty the original configured mapping container after discovery, then route that recursive scope to the discovered CVS roots. This keeps IDEA 232's provider and `ChangelistBuilder` on the same scope anchor; previously the provider found deep changes but the platform silently discarded them before updating the Changes view.

- Add an explicit, cancellable, read-only repository verification for timestamp-only status candidates. A candidate is cached as clean only when a complete `cvs -n update` leaves it silent and its working bytes plus `CVS/Entries` remain stable; login, network, cancellation, warning, or concurrent-file failures leave the previous baseline untouched.

- Collapse overlapping recursive dirty paths (configured container, converted CVS root, and explicit descendants) before scanning. Each CVS subtree and explicit file is submitted to the Changes model at most once per refresh.

- Index every server-reported path once, then check candidate ancestors through a hash set. Repository-verification result matching is bounded by path depth instead of candidates multiplied by reported paths.

### Many changes whose contents are identical

CVS normally records a revision and checkout timestamp in `CVS/Entries`; unlike Git, it does not always retain a complete local comparison index. If copying a working copy rewrites file timestamps, CVS must conservatively report unchanged contents as modified.

The safe default is **VCS → CVS → Verify Local Contents with CVS Repository...**. It uses the configured IDEA CVS login to run a read-only dry-run update, then stores SHA-256 only for timestamp-mismatched files which the server does not report and which remain stable during verification. Working files, `CVS/Entries`, and the repository are not modified. Login/network errors, warnings, cancellation, or concurrent changes do not update the cache.

The older **Trust Current Contents and Build Local Baseline...** action remains an explicit offline escape hatch, but it also accepts pre-existing local edits when `BaseRevisions` is unavailable. Files that differ from an available `CVS/BaseRevisions` copy always remain changed. Use **Clear Local Content Baseline...** to restore conservative CVS status.

### IDEA 2023.2 on macOS: native crash while rolling back

IDEA 2023.2.8 ships JBR 17.0.12. A large expanded Changes tree can trigger JetBrains Runtime issue [JBR-7659](https://youtrack.jetbrains.com/issue/JBR-7659): the macOS accessibility bridge recursively posts tree-expanded events and macOS terminates the IDE with `Too many nested CFRunLoopRuns`. This is a native runtime failure, not a Java exception from the CVS plugin. The initial release removes avoidable file writes and nested CVS UI work; repository-backed rollback is moved to its own cancellable background task, but the plugin cannot replace the IDE runtime.

If VoiceOver/IDE accessibility is not required, JetBrains' workaround is to add `-Dsun.awt.mac.a11y.enabled=false` in **Help → Edit Custom VM Options**, then restart IDEA. Do not use that workaround when VoiceOver is required; it disables the IDE accessibility bridge and can also affect window-management tools that depend on it. In that case, use a newer IDE/JBR containing the runtime fix, and collapse the large Changes tree before a one-time baseline refresh.

**i18n:** English baseline + `*_zh.properties`. With IDEA UI language = Chinese, labels switch automatically (no manual toggle).

Install: Settings → Plugins → ⚙️ → Install Plugin from Disk… → pick the zip → restart.  
Details: [platform notes](docs/platform-2026.2-notes.md) · [Rust roadmap](docs/rust-performance-roadmap.md) · [tasks](docs/task-mechanism.md).

## Updates

After the maintainer publishes the release, add this custom repository in **Settings → Plugins → ⚙ → Manage Plugin Repositories → +**:

```text
https://raw.githubusercontent.com/jieyuexing/intellij-cvs-plugin/main/updatePlugins.xml
```

Confirm the dialog, then check for plugin updates in **Settings → Plugins → Installed**. Install the **CVS (Community)** update and restart if prompted. The same repository applies to IDEA **2023.2** and **2026.2** (builds `232` through `262.*`); it uses this fork's plugin id `io.github.jieyuexing.cvs`. Once configured, future published versions can be installed through the IDE without choosing a ZIP from disk.

Version **262.0.1** includes the passfile LF line-ending fix. Update metadata alone does not prove runtime compatibility; the existing smoke-test boundaries above still apply. Maintainers: see the [release contract](docs/release.md) for preparation, authorization, and the asset-before-index publication order.

## Layout

| Path | Role |
| --- | --- |
| `cvs-core/` | Core CVS client / UI support code |
| `cvs-plugin/` | IntelliJ plugin sources + `META-INF/plugin.xml` |
| `javacvs-src/` | JavaCVS library sources |
| `smartcvs-src/` | SmartCVS-related sources |
| `trilead-ssh2-build213/` | Bundled SSH library sources |
| `lib/` | Prebuilt jars (e.g. trilead) |
| `testSource/` | Tests |
| `docs/` | Platform notes, task boundaries, and future performance roadmap |

## License

Apache License 2.0. See [LICENSE](LICENSE) and [SOURCE.txt](SOURCE.txt) for provenance.
