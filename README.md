# OpenCVS

**English** | [中文](README_ZH.md)

CVS version control for current IntelliJ IDEA releases — a community-maintained fork of JetBrains' frozen CVS plugin.

> **Not affiliated with the OpenBSD OpenCVS project or JetBrains.**

[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34911-opencvs) (listing under review) ·
[Releases](https://github.com/jieyuexing/intellij-cvs-plugin/releases) ·
[Changelog](#changelog) ·
License: [Apache 2.0](LICENSE)

## Features

- Check out, update, commit, compare, browse history and annotate CVS working copies from the IDE.
- Connection types of the original plugin: `pserver`, `ext`, SSH and local repositories.
- English and Chinese UI; labels follow the IDE display language.
- Fixes and additions over the last official release (`223.0`):
  - installs on IntelliJ IDEA **2023.2** through **2026.2**;
  - CVS roots are discovered from disk, so initial status no longer needs “Reload from Disk” on 2023.2;
  - faster repository and status scans for large or nested working copies;
  - read-only repository verification for working copies whose timestamps were rewritten by copying;
  - cancellable, batched rollback with error reporting;
  - `~/.cvspass` is written with LF line endings, so command-line CVS keeps authenticating after an IDE login;
  - canceled or failed content requests no longer produce a whole-file diff.

## Installation

The plugin id is `io.github.jieyuexing.cvs`, different from the official `CVS` plugin. **Disable or uninstall the official CVS plugin** before installing OpenCVS; both register the same actions.

### JetBrains Marketplace

After the listing is approved: **Settings → Plugins → Marketplace**, search for **OpenCVS**.

### GitHub plugin repository (recommended for the latest fixes)

Add this URL in **Settings → Plugins → ⚙ → Manage Plugin Repositories → +**:

```text
https://raw.githubusercontent.com/jieyuexing/intellij-cvs-plugin/main/updatePlugins.xml
```

New versions then appear under **Settings → Plugins → Installed** like any other update. Marketplace versions are reviewed by JetBrains and may arrive later than GitHub releases; both channels publish the same signed ZIP and version numbers.

### Manual

Download `intellij-cvs-plugin-<version>.zip` from [Releases](https://github.com/jieyuexing/intellij-cvs-plugin/releases), then **Settings → Plugins → ⚙ → Install Plugin from Disk…** and restart.

Releases since `262.0.2` are signed with a self-signed certificate ([docs/signing-cert.pem](docs/signing-cert.pem)). Outside the Marketplace the IDE may show the signer as untrusted.

## Compatibility

Install range: build `232` – `262.*` (IntelliJ IDEA 2023.2 – 2026.2). Versions follow the IDEA train: `262.x` is built against IDEA 2026.2.

### Target IDE versions

Only the **Active** rows are built and tested by the maintainer; other versions inside the install range are best effort.

| Priority | IDE | Build line | Status |
| --- | --- | --- | --- |
| **Active (primary)** | IntelliJ IDEA 2026.2.x | `262.*` | Supported; compile target |
| **Active (secondary)** | IntelliJ IDEA 2023.2.8 | `232.*` | Supported; Java 17 bytecode |
| Historical | IntelliJ IDEA 2020.3 – 2022.1.4 | `203` – `221` | Use the official plugin `223.0` |
| Other | — | — | Not guaranteed until added as Active |

## Troubleshooting

### Many changes whose contents are identical

CVS records a revision and a checkout timestamp in `CVS/Entries`; it keeps no full local comparison index. If copying a working copy rewrites file timestamps, CVS must conservatively report unchanged files as modified.

Use **VCS → CVS → Verify Local Contents with CVS Repository...**. It runs a read-only dry-run update with the configured CVS login and records SHA-256 only for timestamp-mismatched files that the server does not report and that stay stable during verification. Working files, `CVS/Entries` and the repository are not modified; errors, warnings, cancellation or concurrent changes leave the cache untouched.

**Trust Current Contents and Build Local Baseline...** remains an offline escape hatch, but it also accepts existing local edits when `CVS/BaseRevisions` is unavailable. **Clear Local Content Baseline...** restores conservative status.

### Diff shows the whole file as changed

Fixed in `262.0.3`. Earlier versions could cache an empty base revision when a content request was canceled. Updating is enough: zero-byte files under `CVS/BaseRevisions/` are now ignored and fetched again.

### Command-line `cvs` fails with “authorization failed” after logging in from the IDE

Fixed in `262.0.1`. Earlier versions rewrote `~/.cvspass` with the IDE's default line separator; with CRLF, GNU CVS sends a wrong password. Convert the file back to LF (`0600` permissions) or log in again after updating.

### IDEA 2023.2 on macOS crashes while rolling back

IDEA 2023.2.8 ships JBR 17.0.12. A large expanded Changes tree can trigger [JBR-7659](https://youtrack.jetbrains.com/issue/JBR-7659): the macOS accessibility bridge recursively posts tree-expanded events and macOS terminates the IDE with `Too many nested CFRunLoopRuns`. This is a runtime failure, not a plugin exception; repository-backed rollback already runs in its own cancellable background task.

If VoiceOver and IDE accessibility are not needed, add `-Dsun.awt.mac.a11y.enabled=false` in **Help → Edit Custom VM Options** and restart. Otherwise use a newer IDE/JBR and collapse the large Changes tree before refreshing.

## Changelog

| Version | Date | Changes |
| --- | --- | --- |
| `262.0.4` | 2026-10-09 | Internal API usages replaced with public APIs or plugin-owned code (30 of 32 reported for IDEA 2026.2); remaining hard-coded UI texts moved to English/Chinese bundles. |
| `262.0.3` | 2026-10-09 | Canceled or failed content requests no longer cache an empty diff baseline; zero-byte `CVS/BaseRevisions` caches are ignored and refreshed. |
| `262.0.2` | 2026-10-09 | Renamed to OpenCVS; original icons; signed distribution for GitHub and the Marketplace. |
| `262.0.1` | 2026-10-09 | `~/.cvspass` is always written with LF line endings. |
| `262.0` | 2026-08-03 | First community release: install range 232–262.*, Java 17 bytecode, disk-based root discovery, faster status scans, repository verification and local content baseline, cancellable batched rollback, Chinese UI. |

## Background

JetBrains moved CVS out of the main IntelliJ IDEA tree in 2019.2, [deprecated it](https://blog.jetbrains.com/idea/2020/11/cvs-integration-deprecation) in 2020-11 and kept the source in [intellij-obsolete-plugins](https://github.com/JetBrains/intellij-obsolete-plugins/tree/master/cvs). The official Marketplace plugin ([10746-cvs](https://plugins.jetbrains.com/plugin/10746-cvs), id `CVS`) is frozen at **`223.0`** (published around 2022-12), compatible with build `203.1` – `221.*` (IntelliJ IDEA 2020.3 – 2022.1.4) and provided as-is.

| When | What |
| --- | --- |
| Before 2019.2 | CVS shipped with IntelliJ IDEA. |
| 2019.2 | Moved to `intellij-obsolete-plugins`. |
| 2020-11 | Deprecation announced. |
| ≈2022-12 | Last official release `223.0`; newer IDEs are outside its range. |
| 2026-07 | This fork starts from the obsolete-plugins source with a new id, vendor `jieyuexing` and version `262.0`. |
| 2026-10 | Renamed to OpenCVS; GitHub update channel and Marketplace submission. |

## Maintenance policy

- This is a community restart, not a promise of support for every IDE release.
- The maintainer prioritizes the IDE versions in the **Active** rows; issues and pull requests for other versions are welcome but may wait for verification.
- Original code and CVS behavior are kept by default; changes are minimal and reversible. Contributor rules: [AGENTS.md](AGENTS.md).

## Status

- [x] Standalone Gradle build (IntelliJ Platform Gradle Plugin 2.18.1, JBR 25) and install range `232` – `262.*`
- [x] Install and root-discovery smoke on IDEA 2023.2.8 and 2026.2
- [x] Chinese UI bundles switched by IDE language
- [x] GitHub update channel and signed releases
- [ ] JetBrains Marketplace approval — first review asked to remove Internal API usages reported by the Plugin Verifier on 2026.2
- [ ] Large-directory rollback smoke on 2023.2.8 and 2026.2 (batched restore, visible Cancel, partial-cancel recovery, no `.#file.revision` leftovers)
- [ ] Rust Phase 0: Java baseline profiling and go/no-go decision ([roadmap](docs/rust-performance-roadmap.md); planning only)

## Development

```bash
# JBR 25 from IntelliJ IDEA 2026.2
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
python3 scripts/check_i18n_keys.py
python3 scripts/check_rust_roadmap.py
./gradlew buildPlugin            # build/distributions/intellij-cvs-plugin-<version>.zip
./gradlew verifyPlugin           # Plugin Verifier against IDEA 2026.2 and 2023.2.8
```

Releases go through `scripts/release.py prepare|publish`; signing, the GitHub update channel and Marketplace uploads are described in [docs/release.md](docs/release.md). Platform notes: [docs/platform-2026.2-notes.md](docs/platform-2026.2-notes.md) · task boundaries: [docs/task-mechanism.md](docs/task-mechanism.md).

| Path | Role |
| --- | --- |
| `cvs-plugin/` | Plugin sources and `META-INF/plugin.xml` |
| `cvs-core/` | Core CVS client and UI support |
| `javacvs-src/` | JavaCVS library sources |
| `smartcvs-src/` | SmartCVS-derived sources |
| `trilead-ssh2-build213/`, `lib/` | SSH library sources and prebuilt jars |
| `testSource/` | Tests |
| `scripts/` | Checks, release and signing tools |
| `docs/` | Platform notes, release contract, roadmap |

## License

The plugin is licensed under the [Apache License 2.0](LICENSE), as is the upstream JetBrains source. Bundled components keep their own licenses: JavaCVS under the Sun Public License ([javacvs-src/sun-public-license.txt](javacvs-src/sun-public-license.txt)) and Trilead SSH-2 under a BSD-style license ([trilead-ssh2-build213/LICENSE.txt](trilead-ssh2-build213/LICENSE.txt)). Provenance: [SOURCE.txt](SOURCE.txt).
