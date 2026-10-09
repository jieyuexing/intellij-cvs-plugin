# intellij-cvs-plugin — Agent 入口

> 本文件是 **本 fork 仓库** 的工作合同，供人类与 Agent 共用。  
> 本目录是 DSH workspace `intellij-cvs-plugin` 的独立 Git 根；进入本仓后以本文件为准。

## 0. 一句话

**尽量保持原有代码与行为；在此基础上做解耦增强；面向用户的 label / 文案资源做全球化，当前只做 en + zh。**

## 1. 项目身份

| 项 | 值 |
| --- | --- |
| 仓库 | https://github.com/jieyuexing/intellij-cvs-plugin |
| 维护者 | jieyuexing |
| Plugin id | `io.github.jieyuexing.cvs`（≠ 官方 `CVS`） |
| 显示名 | OpenCVS |
| 上游源码 | https://github.com/JetBrains/intellij-obsolete-plugins/tree/master/cvs |
| 官方 Marketplace（冻结） | https://plugins.jetbrains.com/plugin/10746-cvs · 最后 **223.0** · IDEA **2020.3 — 2022.1.4** |
| 许可证 | Apache-2.0（见 `LICENSE`、`SOURCE.txt`） |

非 JetBrains 官方；不冒充官方插件或官方更新通道。事实与时间线见 `README.md`。

## 2. 工作原则

### P-01 尽量保持原有代码

- **默认最小 diff**：能不动的 Java 包、类名、方法语义、CVS 协议行为、VCS 集成点，不要改。
- 包名 `com.intellij.cvsSupport2.*`、action id、`administrativeAreaName="CVS"`、磁盘 `CVS/` 元数据语义，**默认保留**（协议与兼容成本高于「去品牌」）。
- 禁止「为了好看」的大规模重命名、包搬迁、风格化重写。
- 上游逻辑有 bug 时：优先 **最小补丁**；大改须先说明动机与回滚面。
- 新增文件优先落在清晰边界（见 P-02），而不是把无关职责塞进巨型历史类。

### P-02 在原有基础上解耦增强

允许且鼓励的增强方向（均须可回滚、可验证）：

| 方向 | 说明 |
| --- | --- |
| 构建独立化 | 从 obsolete-plugins monorepo 片段补齐独立 Gradle / wrapper / 平台依赖 |
| 平台适配 | 按维护者实际使用的 IDEA 版本抬升 API / `sinceBuild` / `untilBuild` |
| 边界解耦 | 抽出连接、配置、UI、VCS 扩展之间的接口或模块边界；**先加后迁**，不一次拆爆 |
| 可测试性 | 为关键路径加测试；测试不依赖真实 CVS 服务器时用 fixture / 假连接 |
| 可观测与诊断 | 日志、错误提示可读；不引入密钥入库 |
| 身份与分发 | plugin id、vendor、版本号、Marketplace 元数据属于 fork 层，可改 |

禁止：

- 无验收的「架构重写」
- 引入与 CVS 无关的重型框架或第二套 VCS 实现
- 把 DSH Host、Task Store、GTD 等外部控制面耦进插件运行时

### P-03 Label / 文案全球化（en + zh）

**范围：「label 相关资源语言」**——用户可见字符串：菜单、按钮、对话框标题/正文、配置页、通知、错误消息、工具窗口标签等。

| 规则 | 要求 |
| --- | --- |
| 权威形态 | IntelliJ 标准 ResourceBundle：`messages/*Bundle.properties` + 语言后缀 |
| **en** | 默认 / 基线：现有 `*.properties`（无后缀）视为 **English** 基线，保持 key 稳定 |
| **zh** | 同步提供 `*_zh.properties` 或 `*_zh_CN.properties`（二选一，全仓统一；推荐 **`_zh`** 覆盖通用中文） |
| key 稳定 | 新增/修改文案只改 value；**不随意改 key**（改 key = 破坏调用方与其它语言） |
| 禁止硬编码 | 新 UI 文案不得新增长期硬编码中英文；应走 Bundle。历史硬编码逐步迁，不要求一次清完 |
| 键集合对齐 | 每个 en key 在 zh 中应有对应条目；缺译时 **暂时回退 en**，并在 PR/提交说明里标 `i18n-gap` |
| 占位符 | `{0}`、`{1}` 等参数顺序与 en 一致；HTML 片段（若有）保持结构等价 |
| 非目标 | 日志/debug、协议常量、CVS 命令字、测试 fixture 专名不强制翻译 |
| 语言扩展 | 第三种语言（ja、…）需维护者明确批准后再加；当前合同只有 **en、zh** |

现有 bundle 入口（修改文案时优先改这里）：

```text
cvs-core/resources/messages/CvsBundle.properties          # en 基线
cvs-core/resources/messages/CvsBundle_zh.properties       # zh（IDEA 中文 UI 自动加载）
javacvs-src/messages/JavaCvsSrcBundle.properties
javacvs-src/messages/JavaCvsSrcBundle_zh.properties
smartcvs-src/messages/SmartCvsSrcBundle.properties
smartcvs-src/messages/SmartCvsSrcBundle_zh.properties
```

校验：`python3 scripts/check_i18n_keys.py`  
任务机制：`docs/task-mechanism.md`（harness Task Store + 本仓 Status）

`plugin.xml` 的 `<description>`、Marketplace 长文案可中英分场景维护，但 **IDE 内 label 以 Bundle 为准**。

### P-04 版本与目标 IDE

- 官方线冻结于 **223.0 / 2020.3–2022.1.4**；本 fork **插件版本号与 IDEA 平台线对齐**（官方 `223.0` → 本仓主目标 **`262.0`** = IDEA 2026.2）。
- **早期只优先维护者实际使用的 IDE 版本**；未列入 `README.md` Active 表的版本不保证。

| 优先级 | 维护者在用版本 | 平台线（约） | 用途 |
| --- | --- | --- | --- |
| **Primary（已钉）** | IntelliJ IDEA **2026.2.1** | **262.*** | 编译 classpath（local 2026.2 + JBR 25 读平台） |
| **Secondary** | IntelliJ IDEA **2023.2.8** | **232.*** | 安装范围含 232；产出 **Java 17** 字节码 |

- 安装元数据：`pluginSinceBuild=232` / `pluginUntilBuild=262.*`（两台维护机都能装）。
- 构建说明：`docs/platform-2026.2-notes.md`。
- 注意：源码按 262 API 适配；232 上个别新 API 路径可能运行期才暴露问题，优先修双端兼容。
- 核心回归路径：checkout / update / commit / diff / history。

## 3. 目录地图（只读导航）

| 路径 | 角色 |
| --- | --- |
| `cvs-plugin/` | 插件入口、`META-INF/plugin.xml`、IDE 集成 |
| `cvs-core/` | 核心 UI / 配置 / 连接支撑 + **主文案 Bundle** |
| `javacvs-src/` | JavaCVS 库源码 + 其 Bundle |
| `smartcvs-src/` | SmartCVS 相关源码 + 其 Bundle |
| `trilead-ssh2-build213/`、`lib/` | SSH 依赖 |
| `testSource/` | 测试 |
| `README.md` | 人类可读（英文）：时间线、官方冻结、维护策略 |
| `README_ZH.md` | 人类可读（中文），与 README 同步维护 |
| `SOURCE.txt` | 出处与身份摘要 |
| `AGENTS.md` | **本文件**：Agent/协作者工作合同 |

## 4. Agent 默认工作流

1. **先读** `README.md` / `README_ZH.md` + 本文件；改文案时打开对应 `*Bundle*.properties`。改 README 时 **en/zh 同提交同步**（或标 `i18n-gap`）。
2. **再改**：优先最小补丁；身份字段（id/vendor）与用户可见文案按 P-03。
3. **双语同步**：改 en value 或新增 key 时，**同提交**更新 zh（或标明 `i18n-gap` 与原因）。
4. **验证**：能构建则 `buildPlugin` / 安装到目标 IDE；至少冒烟文案切换（英文 UI / 中文 UI）。
5. **提交**：本仓独立 Git 根；说明写清动机与是否触及 i18n。未经用户明确要求 **不 force-push、不改远程保护分支历史**。
6. **push / 发版 / Marketplace 上传**：须用户明确授权。

## 5. 硬停止

- 批量重写或重命名历史包，且无「解耦增强」的明确收益与回滚方案。
- 只改 en 不改 zh（又无 `i18n-gap` 说明）的用户可见文案变更。
- 把密钥、token、本机绝对路径、生产环境凭据写入仓库。
- 声称「支持全部最新 IDEA」但未在 README 目标表与 `since`/`until` 中落实。
- 与官方 plugin id `CVS` 冲突的分发身份（本 fork 必须保持自有 id）。

## 6. Definition of Done（单次改动）

- [ ] 行为符合需求；未无关扩大 diff
- [ ] 原有代码路径仍可理解；解耦点有边界说明（若有）
- [ ] 用户可见文案：en 基线 + zh 同步（或已标 `i18n-gap`）
- [ ] 不破坏 CVS 协议 / 工作区 `CVS/` 语义
- [ ] 在当前维护者目标 IDE 上有最小验证说明（构建失败则写明阻塞）
- [ ] `README` / 本文件仅在策略变化时更新，不每次机械改

## 7. 与 DSH Host 的关系

- 本插件源码只由当前 workspace 和本仓 remote 拥有；不得从退役树、Host 根或其它 workspace 运行、回退读取或发布。
- DSH 的 Task/GTD/治理不自动成为本插件的发布门；Host 只负责登记与会话装配，插件构建、测试和发布继续由本仓合同拥有。

## 8. 发版路由

- 发版 / 在线更新 → `python3 scripts/release.py prepare|publish --receipt <仓外任务目录/prepare.json>` → [docs/release.md](docs/release.md)。版本提交与更新 XML 提交分开；先确认 Release ZIP 可下载，再公开新 XML。push / 发版仍须用户明确授权。

- 签名初始化 / 轮换 → `python3 -B scripts/setup_signing.py`（初始化须授权，已有材料拒绝覆盖）；Marketplace 后续发布 → `python3 -B scripts/release.py marketplace --first-upload-completed --receipt <仓外回执>` → [docs/release.md](docs/release.md)「262.0.2：签名与 Marketplace」。首次网页上传由用户执行；上传另行授权。
