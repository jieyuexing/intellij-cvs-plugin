# Platform target: IntelliJ IDEA 2026.2.1

Status: **`compileJava` green + `buildPlugin` green** on IDEA 2026.2.1 (2026-07-31).

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
./gradlew buildPlugin
# -> build/distributions/intellij-cvs-plugin-262.1.zip
```

**Version:** `262.x` = IDEA **2026.2** platform line (same scheme as official `223.0` for 2022.3). **262.1** adds zh ResourceBundles.

### i18n (en + zh)

| File | Role |
| --- | --- |
| `CvsBundle.properties` | English baseline |
| `CvsBundle_zh.properties` | Chinese — auto when IDEA UI locale is zh |
| `JavaCvsSrcBundle_zh.properties` / `SmartCvsSrcBundle_zh.properties` | Secondary bundles |

No manual language switch in the plugin: IntelliJ `DynamicBundle` follows IDE display language.

### Install smoke on 2026.2.1

1. IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk…  
2. Choose `build/distributions/intellij-cvs-plugin-262.1.zip`  
3. Restart; confirm **CVS (Community)** (id `io.github.jieyuexing.cvs`, version **262.1**).  
4. With **Chinese** UI language: Settings → Version Control → CVS / Global Settings should show Chinese labels.  
5. Optional: open a CVS working copy; try Browse / Checkout / Update / History.

## Next steps

1. Manual install smoke on **2026.2.1** (checklist above).
2. Runtime fixes for SOCKS / chooser / confirmation if needed.
3. Secondary target **2023.2.8** only after primary smoke.
