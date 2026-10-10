# 自定义插件仓库发版合同

入口：`python3 scripts/release.py prepare|publish --receipt <仓外任务目录/prepare.json>`。仅依赖 Python 3.9+ 标准库；构建仍使用本仓 Gradle、IDEA 2026.2 与 JBR 25，见 [平台合同](platform-2026.2-notes.md)。只有维护者或获得用户明确 push / 发版授权的协调者可以运行 `publish`；准备、fixture 或构建通过不授予发布权限。脚本不安装插件、不修改 IDEA 设置；Marketplace 更新使用单独的显式发布入口。

## 身份与版本

- 插件 ID：`io.github.jieyuexing.cvs`；名称：`OpenCVS`。
- 版本来自 `gradle.properties`，tag 与版本相同；zip 为 `build/distributions/intellij-cvs-plugin-<version>.zip`。
- 当前安装范围仍为 `232` 至 `262.*`；不得为了通过校验修改兼容范围。
- 版本/tag 只接受无前导零的点分数字稳定版，例如 `262.0.1`。逐段数字比较、缺段补零，与 IntelliJ `VersionComparatorUtil` 的该子集一致；`262.0.0` 与 `262.0` 等价。非数字 tag 不静默忽略，而是停止要求维护者核对。
- ZIP 内唯一的 `META-INF/plugin.xml`（在 `lib/*.jar` 内）的 ID、名称、版本和 since/until 必须匹配本仓身份及 properties。
- 更新索引固定为 `https://raw.githubusercontent.com/jieyuexing/intellij-cvs-plugin/main/updatePlugins.xml`；下载固定为 `https://github.com/jieyuexing/intellij-cvs-plugin/releases/download/<version>/intellij-cvs-plugin-<version>.zip`。

## 准备与提交

先将源码、版本、脚本及双语文档作为版本提交；保留旧的 `updatePlugins.xml`。初次建立通道时，版本提交中不应含新 XML。`prepare` 要求 main、工作树完全干净，本地 origin/main 必须与实时远端 main 一致，且是 HEAD 的祖先（允许相等或本地领先，拒绝落后和分叉）；所有本地及远端 tag 均必须低于新版本，且新 tag 不存在。它只读远端，不 fetch、不推送。

```bash
# 在本仓根执行，任务目录由协调者事先创建，不能使用系统 /tmp。
export TMPDIR="/absolute/path/to/task-directory"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
python3 -B scripts/test_release.py
python3 -B scripts/release.py prepare --receipt "$TMPDIR/prepare.json"
```

`prepare` 顺序运行 `check_i18n_keys.py`、`check_rust_roadmap.py`、`./gradlew compileJava`、`./gradlew buildPlugin`。构建失败即停止；不改构建配置绕过缺失环境。之后核对 ZIP、再次确认本地与远端状态未变，生成根目录 XML，并在指定任务目录写入提交、远端基线、属性和 ZIP/XML 的 SHA-256 回执。回执文件不得覆盖；失败后保留现场，用新名称重新准备。

准备成功后，只把 `updatePlugins.xml` 作为版本提交的一个直属普通提交（无合并提交）：

```bash
git add -- updatePlugins.xml
git commit -m "发布在线更新索引"
```

不要在资产发布前用普通 `git push` 推送这个 XML 提交。保留 prepare 回执与原始 ZIP；后续任何源码修改或重建产物变化都必须重新准备。回执是本地校验记录，不是签名或授权凭证。

## 发布顺序

获得用户明确授权后，协调者在同一干净 main 及原 ZIP 上执行：

```bash
python3 -B scripts/release.py publish --receipt "$TMPDIR/prepare.json"
```

1. 检查回执、版本提交、唯一直属 XML 提交及文件范围、ZIP/XML 哈希；实时远端 main 必须仍为 prepare 基线，新 tag 必须仍不存在；本地 origin/main 必须与实时远端一致，且是版本提交的祖先，保证版本提交和 XML 提交均可快进推送。要求 origin 的读取与推送地址都唯一且为 `git@github.com:jieyuexing/intellij-cvs-plugin.git`。
2. 用精确 SHA 原子推送版本提交至 main 和同版本 tag；不推送 XML，不创建本地 tag，不 force push。
3. `gh release create <version> <zip> --repo jieyuexing/intellij-cvs-plugin --verify-tag` 创建 Release 并上传资产。
4. 对公开 ZIP URL 发起 HEAD，再 GET 核对完整 SHA-256。
5. 再检查本地及远端 main，推送 XML 提交。
6. 取回固定 raw URL，逐字节核对生成的 XML；不自动重试缓存延迟。若暂未一致或读取失败，明确报告此前远端操作全部成功，退出码为 0，但 raw 验证待人工完成，并输出只读复查命令。

因此 IDEA 能读到新版 XML 前，资产已可公开下载且字节一致。期间可能暂时只见旧版本，不能先暴露悬空下载地址。外部维护者仍须避免并发发版及删除/替换已发布资产。

## 失败与人工恢复

除最后 raw XML 人工复查提示外，任何失败立即非零退出，并显示已完成步骤及失败/状态不确定步骤；不自动重试，不回滚远端。命令异常或中断可能发生在服务器已成功、客户端未收到回执之后，不能只按退出码判断远端状态。保留终端输出和 prepare 回执。不要直接重复执行 `publish`：已存在的 tag 或变化的 main 会被拒绝。

1. **校验或首次推送失败：** 只读查看 `git ls-remote origin refs/heads/main refs/tags/<version>`，确认 main/tag 的实际 SHA。尚无远端副作用时修复本地原因、重新准备；若已有 tag，按下一项人工处理。
2. **tag 已有、Release 失败：** 用 `gh release view <version> --repo jieyuexing/intellij-cvs-plugin` 核对是否已创建、资产是否完整。只有确认不存在后才人工创建；缺失资产可在确认同 tag/提交后上传，禁止覆盖同名不同字节资产。发现冲突时停止，不删 tag、不覆盖资产。
3. **资产存在、HEAD/GET 或 XML 推送失败：** 先核对 tag 指向回执版本提交，下载 ZIP 校验 SHA-256；检查远端 main。若仍为版本提交，且本地 HEAD 仍为已验证 XML 提交，明确授权后仅推送该 XML 提交的精确 SHA：`git push origin <xml-commit>:refs/heads/main`。远端已变则人工协调，不 rebase、不 force push。
4. **XML 已推送、raw 检查失败：** 只读核对 main 与固定 raw URL，允许协调者等待 CDN 缓存刷新后手动再验。此时脚本返回 0 并明确提示远端操作已成功、raw 待复查，不表示 raw 验收通过。使用脚本输出的 `curl … | python3 …` 命令与本地 XML 比较，复查退出码 0 才表示一致。不要为缓存延迟重建 Release、重传资产或回滚。

离线测试只证明校验和顺序控制；真实 GitHub 发布、CDN、IDEA 2023.2/2026.2 的更新发现与安装须分别验收。

参考：[JetBrains 自定义插件仓库格式](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html)、[IntelliJ 版本比较实现](https://github.com/JetBrains/intellij-community/blob/master/platform/util-rt/src/com/intellij/util/text/VersionComparatorUtil.java)。

## 262.0.2：签名与 Marketplace

显示名为 **OpenCVS**，与 OpenBSD 的 OpenCVS 项目及 JetBrains 均无隶属关系。名称与 OpenBSD 项目相同，Marketplace 准则 1.2a/1.2c 审核可能要求改名；声明不能保证通过审核。插件 id 与 vendor 保持不变。

### 初始化和保管

获得初始化授权后，在本仓根、已设置 0700 `TMPDIR` 的环境运行 `python3 -B scripts/setup_signing.py`。脚本拒绝既有私钥、证书或三个历史签名条目；不覆盖、不自动轮换。生成 RSA 4096、AES-256-CBC 加密的 PKCS#8 私钥，自签 SHA-256 证书有效期 3650 天。

- 私钥：`~/Library/Application Support/intellij-cvs-plugin/signing/private-key.pem`，目录 0700、文件 0600，由本人拥有，路径不得含 symlink。
- 口令：登录钥匙串 `intellij-cvs-plugin-signing-password`，随机生成，写后回读验证；不存仓库，不进入 argv 或日志。
- 公共证书：[signing-cert.pem](signing-cert.pem)，可公开提交。证书不是秘密。

私钥文件与口令应分开加密备份，并离线验证恢复能力。不要把口令放在私钥旁边；仅备份私钥无法在丢失口令后恢复。轮换需维护者明确授权：先保留旧密钥和证书供历史版本验证，再将旧材料移至受保护备份、移除原条目，重新初始化并提交新证书；联系 Marketplace 支持确认更新签名身份的要求。密钥或口令丢失无法恢复时只能生成新身份并协调信任更新；泄露时立即停止发布并联系 Marketplace 支持。脚本中途失败清理本次文件；钥匙串拒绝/超时后不再访问，明确报告可能残留条目，交维护者处理。删除文件不宣称 APFS/SSD 物理擦除。

Gradle `signing` 使用 `privateKeyFile`、`certificateChainFile` 和环境变量 `OPENCVS_SIGNING_PASSWORD`。2.18.1 默认任务会把口令传入 Java argv，因此本仓替换 `signPlugin` 执行动作，由 `scripts/SigningBridge.java` 在子 JVM 中读取环境变量，再在内存中调用官方 ZIP Signer。不要启用 Gradle debug、build scan 或 configuration cache 记录秘密；release 入口禁用持久 daemon/configuration cache，并抑制或脱敏秘密输出。

`prepare` 先检查私钥权限、证书和钥匙串口令，然后运行既有检查/构建、`signPlugin`、`verifyPluginSignature`。任何缺失或验签失败都停止，不生成 XML/回执。最终签名 ZIP 复制为原渠道约定的 `build/distributions/intellij-cvs-plugin-<version>.zip`；`-signed.zip` 是同字节中间产物，发布入口锁定前者。schema 2 回执记录最终签名 ZIP 哈希；旧 schema 1 回执不能发布。GitHub `publish` 也重新验签。

### 首次网页上传（用户本人执行）

1. 登录 JetBrains Marketplace 账号，确认维护者资料、可用的 vendor URL 和邮箱 `jieyuexing@outlook.com`，阅读并接受 Developer Agreement。
2. 按实际情况填写 trader/non-trader 身份和要求的联系方式。
3. 新建插件页面，上传 prepare 回执锁定的 **262.0.2 签名 ZIP**，核对 id `io.github.jieyuexing.cvs`、名称 OpenCVS、版本、232–262.* 安装范围及图标。
4. 填写源码 `https://github.com/jieyuexing/intellij-cvs-plugin`、Apache-2.0 许可证/EULA（仓库 LICENSE），保留上游版权和非官方/无隶属关系声明。
5. 检查英文描述、change-notes 和联系资料。每次上传均有 Verifier 与人工审核；内部 API、兼容性问题、名称冲突可能阻断，不保证审核时长或通过。
6. 保存页面 URL 与审核结果；首次网页上传完成后才使用后续自动更新入口。本轮不执行上传或安装。

### Marketplace 现场记录

- 插件页：<https://plugins.jetbrains.com/plugin/34911-opencvs>（数字 id `34911`，xmlId `io.github.jieyuexing.cvs`）。审核状态可只读查询 `https://plugins.jetbrains.com/api/plugins/34911` 与 `/api/plugins/34911/updates`（未批准的版本不在公开列表中）。
- 2026-10-09：用户首次网页上传 `262.0.3`（update id `1190253`，`approve=false`）。同日审核回信（工单 #9241280）：Plugin Verifier 报告 Internal API 使用，要求移除后重新上传；未对名称提出异议。本地 `verifyPlugin` 对 IU-262 报告 32 处（`CharsetToolkit`、`ActionsBundle`/`IdeBundle`、`SLRUCache`、`FileChooserFactoryImpl`、`ChangesUtil`、`AbstractVcs.filterUniqueRoots`/`getCustomConvertor` 覆写），IU-232 为 0。
- 上传时插件的 Source code URL 字段为空，需在页面设置中补为 `https://github.com/jieyuexing/intellij-cvs-plugin`（准则 3.3）。
- 2026-10-09：用户上传 `262.0.4`（update `1190288`，工单 #9241812）；3 分钟后自动退回，理由仍是 Internal API（剩余 `AbstractVcs` 两处覆写）。已起草回复，请求公开接口指导或例外。
- 262.0.4（update `1190288`）的 Marketplace 验证结果（Verifier 1.410）：IU-232/233/241/242/243/251/252 共 7 个版本均为 WARNINGS（internal API 0，不阻断）；IU-253.33813.55 与 IU-261.27258.48 为 PROBLEMS，各 3 处；IU-262.10968.63 为 PROBLEMS，2 处。剩余全部是 `CvsVcs2` 对 `AbstractVcs` 的覆写：`getCustomConvertor()`、`filterUniqueRoots(List, Function)`；253/261 另加 `isVersionedDirectory(VirtualFile)`，在 262 中已不再标为 Internal。IDE 实机运行（IDE_PERFORMANCE，IU-262.10968.63）为 OK。
- 2026-10-09：`262.0.5` 先发布到 GitHub 渠道，用户在本机 IDEA 打开 NSN/OSN 快速试用（VCS 映射展开、Changes 列表、diff）反馈正常后，由协调者用 `release.py marketplace` 上传（update `1190327`）。本地 Plugin Verifier 在 IU-232/243/253/261/262 上 internal API 与兼容性问题均为 0；Marketplace 的检查结果见下方查询方法。
- 2026-10-10：`262.0.6`（读超时后不再永久卡住、默认超时 300 秒）由协调者依次运行 `release.py prepare`、`publish`、`marketplace`：GitHub Release 与 raw XML 均校验一致；Marketplace 上传为 update `1191080`，提交时 `approve=false`，等待审核。未先在本机 IDEA 试用。
- 查询方法（需要钥匙串中的 `jetbrains-marketplace-token`，令牌经管道传给 curl，不进入 argv）：`GET https://plugins.jetbrains.com/api/verifications/update/<updateId>?verificationType=INTELLIJ_COMPATIBILITY&verificationType=IDE_PERFORMANCE`，请求头 `Authorization: Bearer <token>`，返回每个 IDE 的 `resultType` 和 `verificationVerdict`。完整报告 `fullVerificationResultUrl` 会 301 跳转到 `https://downloads.marketplace.jetbrains.com/files/<pluginId>/<updateId>/verification/<ideVersion>/<verifier>/verifier.json`，该地址无需认证即可下载，internal API 明细在其中。
- 262.0.3 的 Marketplace Compatibility verification（Verifier 1.410，仅 IntelliJ IDEA）：2026.2.3 的 IDE 实机运行为 Success；静态检查的 internal API 数量为 2026.2.3 32、2026.1.5 24、2025.3.6.1 24、2025.2.6.3/2025.1.7.2/2024.3.7.1 20、2024.2.6 6、2024.1.7/2023.3.8 4、2023.2.8 0（仅 Warnings）。每个版本都只有 1 条 compatibility warning（历史包名），没有 compatibility problem。状态为 Problems 仅由 internal API 引起，scheduled-for-removal 与 deprecated 只算 Warnings。
- 2026-10-09 提交 `5a7ad5e`：`ActionsBundle`/`IdeBundle` 改为插件自有中英文 key，`check_i18n_keys.py` 防回退；IU-262 内部 API 32 → 23，IU-232 仍为 0。剩余 23 处（`CharsetToolkit` 14、`SLRUCache` 4、`FileChooserFactoryImpl` 2、`AbstractVcs` 覆写 2、`ChangesUtil` 1）清零后发 `262.0.4`，再上传 Marketplace。

### 后续 Marketplace 更新（另行授权）

由用户自己写入 token，避免把 token 放在 shell 命令行或历史中；以下命令形态的 `-w` 省略值，由 macOS 提示输入（先确认默认钥匙串为登录钥匙串）：

```bash
security add-generic-password -a intellij-cvs-plugin -s jetbrains-marketplace-token -w
python3 -B scripts/release.py marketplace --first-upload-completed --receipt "$TMPDIR/prepare.json"
```

首次写入令牌可在 Keychain Access 中完成，勿使用含实际 token 的 `-w <token>` 命令。入口检查干净 main、版本/XML 提交关系、回执/ZIP/XML 哈希与签名，再从登录钥匙串读取 `jetbrains-marketplace-token`，只注入 `publishPlugin` 子进程。Gradle 上传任务不重新构建或签名，并再次核对 ZIP SHA-256；令牌缺失明确失败。上传失败不自动重试，先在网页核对服务端状态。`--first-upload-completed` 是操作者对既有网页上传的明确声明，不会替你上传第一版。

两个渠道使用同一个 id、相同版本及同一份签名 ZIP。建议先按 GitHub 资产→更新 XML 的合同发布，再提交同版本 Marketplace 审核；审核期间两个渠道可暂时不同步，不为绕过审核反复修改同版本资产。自签证书在自定义仓库不自动成为 IDE 信任根，用户可能仍需核对并信任公开证书；签名验证成功不证明安装/运行时验收。

### 本地验证入口

```bash
python3 -B scripts/test_release.py
./gradlew --no-daemon --console=plain verifyPlugin
```

Verifier 固定本机 IDEA 2026.2、下载 IDEA 2023.2.8 与中间版本 IDEA 2024.3.6；
加 `-PverifyCrossProduct=true` 同时调查 WebStorm 2026.2。目标已缓存时可加 `--offline`，
避免远端元数据 TLS 故障；仍须区分依赖无法解析和完成扫描。不删除目标绕过错误。报告须区分 compatibility problems、internal API、deprecated/experimental 与环境错误。详细结果由本轮执行记录持有。

来源：[签名文档](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)、[发布文档](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)、[审核准则](https://plugins.jetbrains.com/docs/marketplace/approval-guidelines.html)。


### 2026-10-09：扩大兼容性调查

原描述符在 IU-232.10335.12、IU-243.26574.91、IU-262.10968.63、
WS-262.8665.259 上的 compatibility problems 均为 0；Internal API 分别为
0、20、23、23，每个目标另有 1 条历史包名 warning。首次在线执行因远端元数据
TLS 握手失败；缓存目标后 `verifyPlugin --offline -PverifyCrossProduct=true`
完成扫描，exit 1 的原因是 Internal API。

这没有复现 Marketplace 页面所称的兼容性问题，也没有证明所有声明产品均兼容。
因此暂不增加无行为需要的 IDEA/Java 依赖，不缩窄安装产品；待取得页面的具体
IDE build 与问题条目再精确复现。原始报告由本轮任务 RECORD 持有。
