# 自定义插件仓库发版合同

入口：`python3 scripts/release.py prepare|publish --receipt <仓外任务目录/prepare.json>`。仅依赖 Python 3.9+ 标准库；构建仍使用本仓 Gradle、IDEA 2026.2 与 JBR 25，见 [平台合同](platform-2026.2-notes.md)。只有维护者或获得用户明确 push / 发版授权的协调者可以运行 `publish`；准备、fixture 或构建通过不授予发布权限。脚本不安装插件、不修改 IDEA 设置、不发布 Marketplace。

## 身份与版本

- 插件 ID：`io.github.jieyuexing.cvs`；名称：`CVS (Community)`。
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
