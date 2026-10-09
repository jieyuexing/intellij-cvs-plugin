# OpenCVS

[English](README.md) | **中文**

让新版 IntelliJ IDEA 继续使用 CVS 版本控制——JetBrains 已冻结的官方 CVS 插件的社区维护 fork。

> **与 OpenBSD 的 OpenCVS 项目及 JetBrains 均无隶属关系。**

[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34911-opencvs)（上架审核中）·
[Releases](https://github.com/jieyuexing/intellij-cvs-plugin/releases) ·
[版本记录](#版本记录) ·
许可证：[Apache 2.0](LICENSE)

## 功能

- 在 IDE 中检出、更新、提交、比较、查看历史和逐行注解 CVS 工作副本。
- 支持原插件的连接方式：`pserver`、`ext`、SSH 和本地仓库。
- 提供英文和中文界面，随 IDE 显示语言自动切换。
- 相对最后一个官方版本（`223.0`）的修复与增强：
  - 可安装在 IntelliJ IDEA **2023.2** 至 **2026.2**；
  - 从磁盘发现 CVS 根目录，2023.2 上首次显示状态不再需要“从磁盘重新加载”；
  - 大型或嵌套工作副本的仓库与状态扫描更快；
  - 对因复制而改写时间戳的工作副本提供只读的仓库核对；
  - 回滚可取消、分批执行，并报告错误；
  - `~/.cvspass` 以 LF 换行写入，在 IDE 登录后命令行 CVS 仍能正常认证；
  - 取内容被取消或失败时，不再出现整个文件都算差异的 diff。

## 安装

插件 id 为 `io.github.jieyuexing.cvs`，与官方插件 `CVS` 不同。安装前请**停用或卸载官方 CVS 插件**，两者注册了相同的操作。

### JetBrains Marketplace

审核通过后：**Settings → Plugins → Marketplace**，搜索 **OpenCVS**。

### GitHub 插件仓库（推荐，最快拿到修复）

在 **Settings → Plugins → ⚙ → Manage Plugin Repositories → +** 中添加：

```text
https://raw.githubusercontent.com/jieyuexing/intellij-cvs-plugin/main/updatePlugins.xml
```

之后新版本会和其他插件一样出现在 **Settings → Plugins → Installed** 的更新中。Marketplace 的版本要经过 JetBrains 审核，可能晚于 GitHub 发布；两个渠道使用相同的签名 ZIP 和版本号。

### 手动安装

从 [Releases](https://github.com/jieyuexing/intellij-cvs-plugin/releases) 下载 `intellij-cvs-plugin-<version>.zip`，然后 **Settings → Plugins → ⚙ → Install Plugin from Disk…**，安装后重启。

`262.0.2` 起的版本使用自签名证书签名（[docs/signing-cert.pem](docs/signing-cert.pem)）。不经 Marketplace 安装时，IDE 可能提示签名者不受信任。

## 兼容性

安装范围：build `232` – `262.*`（IntelliJ IDEA 2023.2 – 2026.2）。版本号跟随 IDEA 版本线：`262.x` 基于 IDEA 2026.2 构建。

### 目标 IDE 版本

只有 **Active** 行由维护者构建和测试；安装范围内的其他版本尽力支持。

| 优先级 | IDE | 版本线 | 状态 |
| --- | --- | --- | --- |
| **Active（主）** | IntelliJ IDEA 2026.2.x | `262.*` | 支持；编译目标 |
| **Active（次）** | IntelliJ IDEA 2023.2.8 | `232.*` | 支持；Java 17 字节码 |
| 历史 | IntelliJ IDEA 2020.3 – 2022.1.4 | `203` – `221` | 请使用官方插件 `223.0` |
| 其他 | — | — | 加入 Active 前不作保证 |

## 常见问题

### 大量“内容相同”的改动

CVS 在 `CVS/Entries` 中只记录版本号和检出时间戳，不保留完整的本地比较索引。复制工作副本时如果改写了文件时间戳，CVS 只能保守地把未改动的文件报告为已修改。

使用 **VCS → CVS → 使用 CVS 仓库验证本地内容...**（Verify Local Contents with CVS Repository）。它用已配置的 CVS 登录执行一次只读的 dry-run 更新，只为时间戳不一致、服务器未报告且核对期间保持稳定的文件记录 SHA-256。工作文件、`CVS/Entries` 和仓库都不会被修改；出错、警告、取消或并发改动时缓存保持不变。

**信任当前内容并建立本地基线...**（Trust Current Contents and Build Local Baseline）仍作为离线兜底保留，但在 `CVS/BaseRevisions` 不可用时也会接受已有的本地修改。**清除本地内容基线...**（Clear Local Content Baseline）可恢复保守状态。

### diff 把整个文件都标为改动

已在 `262.0.3` 修复。旧版本在取内容被取消时可能缓存空的基准版本。升级即可：`CVS/BaseRevisions/` 下的 0 字节文件现在会被忽略并重新获取。

### 在 IDE 中登录后，命令行 `cvs` 报 “authorization failed”

已在 `262.0.1` 修复。旧版本按 IDE 默认换行符重写 `~/.cvspass`；换行为 CRLF 时，GNU CVS 会发送错误的口令。把该文件改回 LF（权限 `0600`），或升级后重新登录。

### macOS 上 IDEA 2023.2 回滚时崩溃

IDEA 2023.2.8 自带 JBR 17.0.12。展开很大的 Changes 树可能触发 [JBR-7659](https://youtrack.jetbrains.com/issue/JBR-7659)：macOS 辅助功能桥递归发送树展开事件，系统以 `Too many nested CFRunLoopRuns` 终止 IDE。这是运行时故障，不是插件异常；依赖仓库的回滚已放在独立的可取消后台任务中执行。

如果不需要 VoiceOver 和 IDE 辅助功能，可在 **Help → Edit Custom VM Options** 中加入 `-Dsun.awt.mac.a11y.enabled=false` 后重启。否则请使用更新的 IDE/JBR，并在刷新前折叠大的 Changes 树。

## 版本记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| `262.0.3` | 2026-10-09 | 取内容被取消或失败时不再缓存空的 diff 基准；忽略并刷新 0 字节的 `CVS/BaseRevisions` 缓存。 |
| `262.0.2` | 2026-10-09 | 更名为 OpenCVS；原创图标；GitHub 与 Marketplace 共用签名发行包。 |
| `262.0.1` | 2026-10-09 | `~/.cvspass` 始终以 LF 换行写入。 |
| `262.0` | 2026-08-03 | 首个社区版本：安装范围 232–262.*、Java 17 字节码、从磁盘发现根目录、更快的状态扫描、仓库核对与本地内容基线、可取消的分批回滚、中文界面。 |

## 背景

JetBrains 在 2019.2 把 CVS 移出 IntelliJ IDEA 主仓库，2020-11 [宣布弃用](https://blog.jetbrains.com/idea/2020/11/cvs-integration-deprecation)，源码保留在 [intellij-obsolete-plugins](https://github.com/JetBrains/intellij-obsolete-plugins/tree/master/cvs)。官方 Marketplace 插件（[10746-cvs](https://plugins.jetbrains.com/plugin/10746-cvs)，id `CVS`）冻结在 **`223.0`**（约 2022-12 发布），兼容 build `203.1` – `221.*`（IntelliJ IDEA 2020.3 – 2022.1.4），按现状提供。

| 时间 | 事件 |
| --- | --- |
| 2019.2 之前 | CVS 随 IntelliJ IDEA 一起发布。 |
| 2019.2 | 移入 `intellij-obsolete-plugins`。 |
| 2020-11 | 宣布弃用。 |
| 约 2022-12 | 最后的官方版本 `223.0`；更新的 IDE 不在其兼容范围内。 |
| 2026-07 | 本 fork 以 obsolete-plugins 源码起步，使用新 id、vendor `jieyuexing`、版本 `262.0`。 |
| 2026-10 | 更名为 OpenCVS；建立 GitHub 更新渠道并提交 Marketplace。 |

## 维护策略

- 这是社区重启维护，不承诺支持每一个 IDE 版本。
- 维护者优先保证 **Active** 行中的 IDE 版本；欢迎针对其他版本的 issue 和 PR，但可能要等到能够验证时才处理。
- 默认保留原有代码和 CVS 行为，改动保持最小、可回退。协作规则见 [AGENTS.md](AGENTS.md)。

## 状态

- [x] 独立 Gradle 构建（IntelliJ Platform Gradle Plugin 2.18.1、JBR 25），安装范围 `232` – `262.*`
- [x] 在 IDEA 2023.2.8 与 2026.2 上完成安装与根目录发现冒烟验证
- [x] 中文界面资源随 IDE 语言切换
- [x] GitHub 更新渠道与签名发行
- [ ] JetBrains Marketplace 审核通过——首次审核要求移除 Plugin Verifier 在 2026.2 上报告的内部 API 使用
- [ ] 2023.2.8 与 2026.2 上的大目录回滚冒烟（分批恢复、可见的取消、部分取消后的恢复、不残留 `.#file.revision`）
- [ ] Rust 第 0 阶段：Java 基线 profiling 与是否推进的决定（[路线图](docs/rust-performance-roadmap.md)；仅规划）

## 开发

```bash
# 使用 IntelliJ IDEA 2026.2 自带的 JBR 25
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
python3 scripts/check_i18n_keys.py
python3 scripts/check_rust_roadmap.py
./gradlew buildPlugin            # build/distributions/intellij-cvs-plugin-<version>.zip
./gradlew verifyPlugin           # 用 Plugin Verifier 检查 IDEA 2026.2 与 2023.2.8
```

发版通过 `scripts/release.py prepare|publish` 完成；签名、GitHub 更新渠道和 Marketplace 上传见 [docs/release.md](docs/release.md)。平台说明：[docs/platform-2026.2-notes.md](docs/platform-2026.2-notes.md) · 任务边界：[docs/task-mechanism.md](docs/task-mechanism.md)。

| 路径 | 作用 |
| --- | --- |
| `cvs-plugin/` | 插件源码与 `META-INF/plugin.xml` |
| `cvs-core/` | CVS 客户端核心与界面支持 |
| `javacvs-src/` | JavaCVS 库源码 |
| `smartcvs-src/` | 源自 SmartCVS 的代码 |
| `trilead-ssh2-build213/`、`lib/` | SSH 库源码与预编译 jar |
| `testSource/` | 测试 |
| `scripts/` | 检查、发版与签名工具 |
| `docs/` | 平台说明、发版合同、路线图 |

## 许可证

插件采用 [Apache License 2.0](LICENSE)，与上游 JetBrains 源码一致。随附组件保留各自许可证：JavaCVS 使用 Sun Public License（[javacvs-src/sun-public-license.txt](javacvs-src/sun-public-license.txt)），Trilead SSH-2 使用 BSD 类许可证（[trilead-ssh2-build213/LICENSE.txt](trilead-ssh2-build213/LICENSE.txt)）。来源说明见 [SOURCE.txt](SOURCE.txt)。
