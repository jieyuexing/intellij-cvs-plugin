# intellij-cvs-plugin

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
| **2026-07** | This fork starts: source taken from obsolete-plugins/cvs, new id `io.github.jieyuexing.cvs`, vendor `jieyuexing`, independent versioning from `1.0.0-SNAPSHOT`. |

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
| New id, new series | Community builds use `io.github.jieyuexing.cvs` and `1.x` (or later) versioning — not JetBrains `223.0`. |
| **Maintainer-first targets** | Early on, **only IntelliJ / platform versions that the maintainer actually uses** will be prioritized for build, smoke test, and fix. |
| Others welcome but not guaranteed | Issues and PRs for other IDE versions are welcome; they may wait until the maintainer can run that version, or until a contributor owns the verification. |
| No “supports all latest IDEA” claim | Compatibility is whatever is currently listed in `sinceBuild` / `untilBuild` and the table below — nothing more. |

### Target IDE versions (living table)

Update this table when a version is adopted or dropped.

| Priority | IDE / platform | Status | Notes |
| --- | --- | --- | --- |
| Baseline (upstream last) | IDEA `2020.3` – `2022.1.4` / builds `203` – `221` | Historical (official 223.0) | Reference only; not the fork’s main track |
| Source tree default | Gradle fragment targets `2022.3` | Unverified | From obsolete-plugins copy; build wiring incomplete |
| **Active maintainer targets** | *TBD — fill with versions you use* | Planned | e.g. the IDEA build on your daily machine |
| Out of scope for now | Versions the maintainer does not run | Best-effort / later | No commitment until adopted in this table |

When you pick a daily IDE, add a row here and set `intellij { version }` / `sinceBuild` / `untilBuild` accordingly.

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

## Status

Early fork setup:

- [x] Source copied from archive
- [x] Fork identity (id / vendor / description)
- [x] Document official freeze (223.0 / 2020.3–2022.1.4) and restart timeline
- [x] `AGENTS.md` (preserve code / decouple / en+zh labels)
- [ ] Standalone Gradle build (settings, wrapper, intellij plugin wiring)
- [ ] First maintainer-target IDE chosen and listed above
- [ ] `CvsBundle_zh.properties` (and sibling bundles) initial pass
- [ ] Verified build / install on that target
- [ ] Optional Marketplace (community) publish

## Build (not ready yet)

The original `build.gradle.kts` was a fragment of the multi-plugin obsolete-plugins monorepo. Standalone build wiring is the next step.

```bash
# planned
./gradlew buildPlugin
```

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

## License

Apache License 2.0. See [LICENSE](LICENSE) and [SOURCE.txt](SOURCE.txt) for provenance.
