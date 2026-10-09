# Internal API 改造与根映射迁移（262.0.5，2026-10-09）

本轮移除 `CvsVcs2` 的 `getCustomConvertor()`、`filterUniqueRoots()`、
`isVersionedDirectory()` 三个覆写，使用公开 `VcsRootChecker`、
`allowsNestedRoots()` 与 `ProjectLevelVcsManager.setDirectoryMappings()`。
安装范围仍为 `232`–`262.*`，id、历史包名和 action id 不变。
此前 262.0.3 的停止结论已由本轮用户授权的映射迁移方案取代。

## 静态验证与测试边界

Verifier 1.410，五个完整发行包；253/261 另核对官方下载 SHA-256。

| IDEA build | Internal API（262.0.4 → 262.0.5） | Compatibility problems |
| --- | --- | --- |
| IU-232.10335.12 | 0 → 0 | 0 |
| IU-243.26574.91 | 0 → 0 | 0 |
| IU-253.33813.55 | 3 → 0 | 0 |
| IU-261.27258.48 | 3 → 0 | 0 |
| IU-262.10968.63 | 2 → 0 | 0 |

每个目标仍有一条历史包名 warning；deprecated / scheduled-for-removal 不是本轮
清零范围。`verifyPlugin --offline` 完成扫描并 exit 0，不以忽略 internal API 或
缩小安装范围通过。Gradle 默认下载精确版本，也支持 `-Pverifier253Path=...`、
`-Pverifier261Path=...` 指向已校验发行包，以便离线复验。

原有七个 Python 测试、i18n 检查、发布脚本二十个测试继续通过。新增：

- `root_mappings_test.py`：编译真实扫描器、映射计划和公开 root checker；覆盖磁盘标记、
  兄弟/独立嵌套/普通子目录、Entries.Log、不同模块、symlink、取消、其他 VCS/none、
  默认映射、根设置、幂等和空结果，并运行原 `FindAllRootsHelperTest`。
- `root_discovery_lifecycle_test.py`：编译完整生产发现器，用可控 EDT/后台队列证明完整
  结果才发布；覆盖取消、IO 错误、VFS 根消失、用户并发改映射、停用与重新启用。
- `change_scope_test.py`：提取实际 Changes 调度及 scope 方法，覆盖 232 的 scope 根
  不属于自身、显式目录、独立嵌套根和重叠去重。Entries/IDE 服务由替身提供。

旧覆写被新断言拒绝；旧扫描器漏掉未显式映射的独立嵌套根；故意删除嵌套调度的坏样本
漏掉 nested，均有红样本记录。fixture / 编译 / verifier 不等于 IDE 真实 Changes、
CVS 协议或业务验收；本轮按任务限制未安装、未访问 CVS 服务器。

## 迁移行为

1. 从当前原始目录映射取扫描输入，不依赖平台已经认可的 CVS 根，因此旧容器映射即使
   暂时不是有效根，也会被后台发现。默认 `<Project>` 映射扫描项目根与内容根。
2. 从磁盘识别 Entries / Root / Repository；普通 Entries 子目录归父根，独立嵌套副本
   成为单独映射。不跟随目录 symlink，不进入 CVS 管理目录，也不扫描其他 VCS/none
   的显式子树；更深的显式 CVS 映射仍可作为独立扫描入口。
3. 扫描、定向 VFS 刷新全部成功后才在 EDT 发布。任何读盘错误、取消、根消失、停用，
   或扫描期间目录映射改变，都不发布旧结果；新配置会重新排队。同一输入取消/失败
   后不自动反复重试，重开项目或改变映射可再次扫描。
4. 有根的显式容器映射自动替换为逐根映射；空结果保留原配置。已有真实根、嵌套根、
   其他 VCS/none 与根设置保留，重复执行不重复添加。默认映射保留，再增加明确根。
   用户无需手工迁移。迁移后 Settings 显示具体根，显式容器不再作为持续的通配映射；
   后续新检出的兄弟副本通过 IDEA 根检测/检出配置登记，也可新增容器映射再次展开。
5. 发现独立嵌套副本需要检查真实根内的子目录，因此首次扫描可能比旧版更多；仍在可取消
   后台任务中执行，正常状态刷新不重复全盘发现。
6. 232 原有的显式 scope 根与递归树处理保留；现在 dirty scope 锚点与平台登记的真实
   根一致。完整快照发布及平台映射刷新后标脏所有实际根。Changes 另外调度独立嵌套根，
   共享 visited 集合避免重复，不把嵌套根当作父副本的 unversioned 目录。

公开 API 合同依据：
[232 VcsRootChecker](https://github.com/JetBrains/intellij-community/blob/idea/232.10335.12/platform/vcs-api/src/com/intellij/openapi/vcs/VcsRootChecker.java)、
[232 ProjectLevelVcsManager](https://github.com/JetBrains/intellij-community/blob/idea/232.10335.12/platform/vcs-api/src/com/intellij/openapi/vcs/ProjectLevelVcsManager.java)、
[262 AbstractVcs](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/vcs-api/src/com/intellij/openapi/vcs/AbstractVcs.java)。

## 如何回退映射

`CvsRootMappingHistory` 项目服务在每次改写前记录全体映射的 before/after 路径及 VCS 名称，
随项目 workspace 状态保存；目录项目通常位于 `.idea/workspace.xml` 的同名 component。
记录不含 CVS 连接字符串、口令或工作副本内容。实际 VCS 配置仍由 IDEA 保存到 `vcs.xml`。

回退时先关闭项目并备份当前 `vcs.xml` 与 workspace 状态；停用 262.0.5 或恢复先前插件，
防止恢复的容器立即再次迁移。按记录从后往前，仅对该次 before/after 的 **CVS 差量**
恢复：移除本次新增且仍保持 after 值的根，补回本次移除且没有被用户重配的原容器；
保留其他 VCS 和迁移后的人工改动。有同路径冲突先比较，不覆盖。也可在旧版插件的
Settings → Version Control 中按相同差量恢复。未迁移的项目不需要回退。

只降级插件通常仍能使用展开后的真实根；恢复容器用于需要原配置表示的情形。
记录应保留到双版本人工验收和回退窗口结束，不自动清理。

## 262.0.5 迁移专项冒烟（尚未执行）

在 2023.2.8 与 2026.2 分别记录精确 build、ZIP SHA、迁移前后映射、workspace 记录与日志：

- 升级已有容器映射项目：兄弟副本、版本化普通子目录、Entries 外的嵌套独立副本，
  确认不需 Reload from Disk 或手工重配，Changes 出现预设修改且无重复。
- 混合 Git / CVS / none 与显式嵌套 CVS 映射，确认没有越界映射/Changes。
- 普通默认 `<Project>` 映射、模块在项目目录外、中文/空格路径；反复打开和映射事件后
  映射及历史记录不重复增长。
- 大目录扫描中取消、改映射、关闭项目；确认旧配置不被半份或陈旧结果覆盖。
- 创建根后 VFS 尚未加载 CVS 目录；确认磁盘发现、映射生效、232 scope 根和所有嵌套
  Changes 均一致。初次扫描耗时与 UI 响应单独记录。
- 按回退步骤恢复原容器并保留迁移后的其他 VCS 人工改动，然后再次迁移核对幂等性。

## 人工 IDE 冒烟清单（尚未执行）

分别在 IDEA 2023.2.8 与 2026.2 上执行并记录精确 build、插件提交/ZIP SHA、结果与日志。
本清单不构成安装、远端提交或修改真实工作副本的授权；使用用户指定的隔离测试仓库与
可丢弃工作副本，执行各自已经批准的操作。

- **检出：** 普通目录与含中文/空格目录；选择器输入用户宏，确认 `$NAME$` 补全与展开，
  空宏不产生异常。检出足够多目录以触发缓存淘汰，核对 Entries 全部落盘、重复访问不丢记录。
- **更新：** 普通修改、冲突、二进制与各编码文本，核对文件字节、状态和换行；取消后不把
  部分内容作为有效基准。失败应显示错误，不宣称成功。
- **提交：** 在已授权的测试仓库提交最小改动，核对实际 revision、日志与本地状态。
  未授权时只检查提交预览并标记真实提交未验收。
- **diff：** UTF-8（有/无 BOM）、UTF-16LE/BE、UTF-32LE/BE、系统回退编码；覆盖空文件、
  非 ASCII 和截断多字节内容，分别与同版本改造前插件比较。特别核对 232 与 262 的
  历史截断处理差异得到保留，没有整个文件误判或基准缓存污染。
- **历史：** 正常文件、已删除文件以及 VFS 尚未加载的父目录，核对历史内容和分支标签；
  缺失路径逐级回退，无读锁内同步刷新异常。
- **回滚：** 缓存命中与需取源、多文件、大目录、取消与错误；核对文件、Entries、剩余修改、
  VFS 状态以及无遗留 `.#file.revision`。保留平台文档中的 232 JBR 崩溃问题独立记录。
- **根发现：** 单个工作副本、显式映射容器下的多个兄弟根、嵌套根、重叠映射、被忽略且尚未
  加入 VFS 的 CVS 目录。首次打开无需 Reload from Disk 即显示全部真实修改；生成/runtime
  兄弟目录不误报。取消扫描不发布半份根集合，改变映射与重开项目后可重新发现。
- **编码设置页：** 英文与中文 UI，默认系统编码、选择后保存/重开、可用编码列表顺序与项目
  编码回退；没有新硬编码文案。确认 diff/更新实际使用所选编码。

构建、fixture 和 verifier 通过只证明相应静态/离线边界，本轮未执行上述 IDE 或 CVS 业务验收。
