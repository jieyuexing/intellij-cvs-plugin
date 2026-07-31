# Platform target: IntelliJ IDEA 2026.2.1

Status: **build wired, first compile not green** (API lift in progress).

## Nailed configuration

| Item | Value |
| --- | --- |
| IDE | IntelliJ IDEA **2026.2.1** (IU) |
| Build | `262.9437.22` (local product-info) |
| Plugin `since` / `until` | `262` / `262.*` |
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

## First `compileJava` snapshot (2026-07-31)

Against local 2026.2.1 + JBR 25:

- Roughly **~100** compile errors across **~70** source files (upstream last targeted ~2022.3 era).
- Dominant themes (not exhaustive):

| Theme | Examples |
| --- | --- |
| Removed / moved VCS API | `com.intellij.openapi.vcs.vfs.*`, `VcsBalloonProblemNotifier`, filter component base classes |
| UI / chooser API | `FileSystemTreeFactory.SERVICE`, `FileLabel`, cell renderer hierarchy |
| Settings / lazy value | `AtomicNotNullLazyValue` visibility, `VcsConfiguration` fields/methods |
| Third-party was bundled | `com.michaelbaranov.microba.calendar.DatePicker` (no longer on platform CP) |
| Signature drift | `ReadOnlyAttributeUtil.setReadOnlyAttribute`, committed-changes filter editors |

**Policy:** keep original code structure; fix with **minimal API adapters** per call site or thin compatibility helpers — no big-bang rewrite (see `AGENTS.md` P-01 / P-02).

## Next steps

1. Green `compileJava` on 2026.2.1 (adapters + missing deps).
2. `buildPlugin` + install into 2026.2.1; smoke CVS paths.
3. Only then re-open 2023.2.8 as secondary.
