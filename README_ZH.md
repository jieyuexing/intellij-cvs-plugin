# intellij-cvs-plugin

[English](README.md) | **中文**

IntelliJ **CVS** 集成插件的社区维护 fork，维护者：**jieyuexing**。

**与 JetBrains 无隶属关系。**

## 源自（Fork of）

上游（JetBrains 原始源码）：

https://github.com/JetBrains/intellij-obsolete-plugins/tree/master/cvs

官方 Marketplace 列表（已冻结）：

https://plugins.jetbrains.com/plugin/10746-cvs

本仓库是 obsolete-plugins 中该目录的独立维护 fork（Apache-2.0）。  
许可证：[Apache License 2.0](LICENSE)

## 为什么要 fork

JetBrains 已弃用 CVS 支持，把代码迁到 `intellij-obsolete-plugins`，并不再推进 Marketplace 版本。**官方插件不会跟上新版 IntelliJ 平台。** 本项目用新的 plugin id 重新开始社区维护，方便仍在用 CVS 的用户在较新 IDE 上继续工作。

## 官方插件状态（JetBrains）

| 项 | 值 |
| --- | --- |
| Marketplace | [CVS · plugin 10746](https://plugins.jetbrains.com/plugin/10746-cvs) |
| Plugin id | `CVS` |
| **最后发布** | **`223.0`**（Marketplace update id 268690） |
| **兼容范围** | **build `203.1` — `221.*`** |
| **IntelliJ IDEA** | **`2020.3` — `2022.1.4`** |
| 大致发布时间 | 2022-12（Marketplace `cdate`） |
| 官方姿态 | 已弃用 / as-is；源码在 obsolete-plugins |

**含义：** 官方线实质上**永久停在 223.0**，**不覆盖** IDEA 2022.2+（build `222.*` 及之后）。新工作只发生在本社区 fork（或其他 fork），而不是 plugin 10746。

## 时间线

| 时间 | 事件 |
| --- | --- |
| 2019 以前 | CVS 在 IntelliJ IDEA 主源码树中，随 IDE 发布。 |
| 2019.2+ | 大量少用插件（含 CVS）迁出主仓，进入 [intellij-obsolete-plugins](https://github.com/JetBrains/intellij-obsolete-plugins)。 |
| 2020-11 | JetBrains 发布 [CVS integration deprecation](https://blog.jetbrains.com/idea/2020/11/cvs-integration-deprecation)；CVS 不再是产品优先级。 |
| 约 2020.3 – 2022.1 | 官方 Marketplace 包仍可在该窗口内的 IDEA 上安装（223.0 的 `since`/`until`）。 |
| **2022-12** | 官方 Marketplace 发布 **`223.0`** —— **已知最后一版 JetBrains 线**。兼容上限 **`221.*` / IDEA 2022.1.4**。 |
| 2023+ | 平台继续前进（`222`、`223`、`231`…）。官方 CVS 停在 223.0；更新 IDE 在声明兼容范围内无法使用。 |
| **2026-07** | 本 fork 启动：源码取自 obsolete-plugins/cvs，新 id `io.github.jieyuexing.cvs`，vendor `jieyuexing`，版本 **`262.0`** 与 IDEA **2026.2**（平台 `262`）对齐。 |

```text
  IDE 内置 / 主源码树              obsolete-plugins + Marketplace
  ─────────────────────►│◄──────────────────────────────────────
                        │
                     2019.2+
                        │
                 弃用说明博客（2020-11）
                        │
              官方 223.0 冻结（≈2022-12）
              203.1 ──────── 221.* / 2022.1.4
                        │
                        ▼
              社区 fork 重新维护（2026-07 →）
              早期仅覆盖维护者实际使用的 IDE 版本
```

## 维护策略（早期）

这是一次**重启**，不是「全版本长期支持」的承诺。

| 规则 | 含义 |
| --- | --- |
| 官方线仅作历史 | 不要指望 JetBrains 再发超过 223.0 / 2022.1.4 的版本。 |
| 新 id；版本对齐 IDEA 平台线 | Plugin id 为 `io.github.jieyuexing.cvs`。**版本主号与 IDEA 平台线一致**（官方最后 `223.0` = 2022.3；本 fork **`262.0`** = 2026.2）。 |
| **维护者优先目标** | 早期只优先**维护者本人实际使用的** IntelliJ / 平台版本：构建、冒烟、修 bug。 |
| 其它版本欢迎但不保证 | 其它 IDE 版本的 Issue / PR 欢迎；可能要等维护者能跑到该版本，或由贡献者负责验证。 |
| 不宣称「支持全部最新 IDEA」 | 兼容性以当前 `sinceBuild` / `untilBuild` 与下表为准，不多写。 |

### 目标 IDE 版本（活表）

版本采纳或淘汰时更新本表。  
维护者机器当前只跑下面两行 **Active**。

| 优先级 | IDE / 平台 | 大致 build 线 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| 基线（上游最后） | IDEA `2020.3` – `2022.1.4` | `203` – `221` | 历史（官方 223.0） | 仅作对照；不是本 fork 主线 |
| 源码树默认 | obsolete-plugins 片段 | `2022.3` / `223` | 未验证 | Gradle 尚未独立；不是维护者安装目标 |
| **Active（主）** | **IntelliJ IDEA `2026.2.1`** | **`262.*`**（本机 `262.9437.22`） | **构建已绿** | `compileJava` + `buildPlugin` 通过；安装冒烟待做 |
| **Active（次）** | **IntelliJ IDEA `2023.2.8`** | **`232.*`** | **已采纳（靠后）** | 主目标变绿后再冒烟 |
| 暂不覆盖 | 其它 IDE 版本 | — | 尽力 / 以后 | 未写入 Active 前不作承诺 |

**早期构建策略**

1. ~~为 **2026.2.1** 接独立 Gradle~~ — **已完成**（见 [docs/platform-2026.2-notes.md](docs/platform-2026.2-notes.md)）。  
2. 在 **2026.2.1** 上把 `compileJava` / `buildPlugin` 做绿（最小 API 适配）。  
3. 安装冒烟 **2026.2.1**，再 **2023.2.8**。  
4. 两边都通后再放宽 `since`/`until`。官方 `223.0` 范围仅作历史。

## 身份（维护者 = jieyuexing）

| 字段 | 值 |
| --- | --- |
| Plugin ID | `io.github.jieyuexing.cvs` |
| 显示名 | CVS (Community) |
| Vendor | `jieyuexing` |
| Vendor URL | https://github.com/jieyuexing |
| Group | `io.github.jieyuexing` |
| 上游 plugin id（JetBrains） | `CVS` |
| 上游 Marketplace | [10746-cvs](https://plugins.jetbrains.com/plugin/10746-cvs) 最后 **223.0** |

本 fork 的 **plugin id 与官方 `CVS` 不同**，不会替换或冲突官方 Marketplace 列表。若两套都装，请卸掉官方 CVS 插件。

## Agent / 协作者合同

工作规则见 **[AGENTS.md](AGENTS.md)**：

1. **默认尽量保持原有代码**（最小 diff；保留 CVS 协议与包布局）。  
2. **在此基础上解耦增强**（构建、平台适配、可测试性）——禁止无验收大翻。  
3. **用户可见 label 全球化**：当前英文基线 + 中文（`en` + `zh`）。

人类可读文档：

| 语言 | 文件 |
| --- | --- |
| English | [README.md](README.md) |
| 中文 | [README_ZH.md](README_ZH.md)（本文件） |

## 状态

早期 fork 搭建：

- [x] 从归档复制源码  
- [x] Fork 身份（id / vendor / description）  
- [x] 文档：官方冻结（223.0 / 2020.3–2022.1.4）与重启时间线  
- [x] `AGENTS.md`（保原码 / 解耦 / en+zh 文案）  
- [x] 维护者目标：IDEA **2026.2.1**（主）、**2023.2.8**（次）  
- [x] 中文 README（本文件）  
- [x] 独立 Gradle（Platform Plugin 2.18.1、Java 25、`since`/`until` = `262` / `262.*`）  
- [x] **2026.2.1** 上 `compileJava` 变绿  
- [x] `buildPlugin` 变绿（版本 **262.1**，含中文 Bundle）  
- [x] `CvsBundle_zh` 及兄弟 bundle；随 IDEA 界面语言自动切换（`DynamicBundle`）  
- [x] Task 机制：[docs/task-mechanism.md](docs/task-mechanism.md) + harness Task `task-intellij-cvs-i18n-zh-v1`  
- [ ] **2026.2.1** 安装冒烟（中文 UI 下重装 262.1 zip）  
- [ ] **2023.2.8** 安装冒烟  
- [ ] 可选：社区 Marketplace 发布  

## 构建（2026.2.1）

```bash
# 使用 IDEA 2026.2.1 自带的 JBR 25
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
python3 scripts/check_i18n_keys.py
./gradlew buildPlugin
# 产物: build/distributions/intellij-cvs-plugin-262.1.zip
```

**版本约定：** 与官方最后一版 **`223.0`**（IDEA 2022.3 / 平台 223）同一套接法——插件主号跟 IDEA 平台线。社区当前主目标是 IDEA **2026.2** → **`262.x`**；**262.1** 增加中文本地化。

**多语言：** 英文基线 + `*_zh.properties`。IDEA 界面语言为中文时，文案自动切换（无需插件内开关）。

安装：设置 → 插件 → ⚙️ → 从磁盘安装插件… → 选 zip → 重启。  
细节见 [docs/platform-2026.2-notes.md](docs/platform-2026.2-notes.md) · 任务：[docs/task-mechanism.md](docs/task-mechanism.md)。

## 目录结构

| 路径 | 角色 |
| --- | --- |
| `cvs-core/` | 核心 CVS 客户端 / UI 支撑代码 |
| `cvs-plugin/` | IntelliJ 插件源码 + `META-INF/plugin.xml` |
| `javacvs-src/` | JavaCVS 库源码 |
| `smartcvs-src/` | SmartCVS 相关源码 |
| `trilead-ssh2-build213/` | 捆绑的 SSH 库源码 |
| `lib/` | 预编译 jar（如 trilead） |
| `testSource/` | 测试 |

## 许可证

Apache License 2.0。出处见 [LICENSE](LICENSE) 与 [SOURCE.txt](SOURCE.txt)。
