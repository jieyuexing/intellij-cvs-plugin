# intellij-cvs-plugin

Community fork of the IntelliJ **CVS** integration plugin, maintained by **jieyuexing**.

**Not affiliated with JetBrains.**

## Fork of

Upstream (original JetBrains source):

https://github.com/JetBrains/intellij-obsolete-plugins/tree/master/cvs

This repository is an independent maintenance fork of that tree (Apache-2.0).  
License: [Apache License 2.0](LICENSE)

## Why this fork

JetBrains deprecated CVS support and moved the plugin into the obsolete-plugins repository. This project continues maintenance for people and teams that still use CVS repositories.

## Identity (maintainer = jieyuexing)

| Field | Value |
| --- | --- |
| Plugin ID | `io.github.jieyuexing.cvs` |
| Display name | CVS (Community) |
| Vendor | `jieyuexing` |
| Vendor URL | https://github.com/jieyuexing |
| Group | `io.github.jieyuexing` |
| Upstream plugin id (JetBrains) | `CVS` |

This **plugin id differs** from the original JetBrains id (`CVS`), so it will not replace or conflict with the marketplace listing of the official plugin. Uninstall the official CVS plugin if both would be installed.

## Status

Early fork setup:

- [x] Source copied from archive
- [x] Fork identity (id / vendor / description)
- [ ] Standalone Gradle build (settings, wrapper, intellij plugin wiring)
- [ ] Verified build against a target IntelliJ version
- [ ] Publish to GitHub / optional Marketplace

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
