# Internal API 改造交接（2026-10-09）

本轮保持版本 `262.0.3`、安装范围 `232`–`262.*`，未 prepare、签名、安装或发布。
静态检查剩余两处根映射覆写，按任务书停止，等待协调者与用户决定。

## 已完成与 verifier 边界

| 目标 | compatibility problems（前 → 后） | Internal API（前 → 后） |
| --- | --- | --- |
| IU-232.10335.12 | 0 → 0 | 0 → 0 |
| IU-243.26574.91 | 0 → 0 | 20 → 0 |
| IU-262.10968.63 | 0 → 0 | 23 → 2 |
| WS-262.8665.259 | 0 → 0 | 23 → 2 |

四目标各有一条原有包名 warning；弃用与计划移除 API 仍存在。
最终 `verifyPlugin --offline -PverifyCrossProduct=true` 为 exit 1，原因是剩余 Internal API。
在线首轮因远端元数据 TLS 握手失败而无判定；离线扫描使用已经完整下载的发行包。

本次没有复现 Marketplace 所称的缺类、缺方法或缺模块问题，不据此收窄安装产品。
这不证明未测 IDE 都兼容；应取得页面列出的具体 IDE build 与错误后继续精确复现。

已替换 CharsetToolkit、SLRUCache、FileChooserFactoryImpl.getMacroMap、
ChangesUtil.findValidParentAccurately。编码与缓存使用实际 IDEA 原类做离线等价对照；
测试反射仅存在于测试 harness，插件生产代码没有通过反射继续调用内部 API。
编码工具用公开 ApplicationInfo 区分 253 前后的截断 UTF-8 行为；稳定版源码边界与
232/243/262 实物对照均已核对，未覆盖所有历史 EAP。

## 为什么保留 AbstractVcs 两个覆写

`CvsVcs2.getCustomConvertor()` 委托 `CvsRootDiscovery.convertRoots()`：它把用户显式
配置的容器映射转换为磁盘发现的 CVS 工作副本根；后台扫描完成后才发布完整快照，
保留取消语义，并将 dirty scope 锚定在原容器，避免 IDEA 232 丢失子根变更。
直接删除将恢复平台的默认根列表，不能保持这一行为。

262 的 [AbstractVcs 源码](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/vcs-api/src/com/intellij/openapi/vcs/AbstractVcs.java)
确实把两个方法标为 Internal；这不是已证实的 verifier 误报。它建议根检测改用
`VcsRootChecker.isRoot` / `detectProjectMappings`。
但 [232 的 VcsRootChecker 合同](https://github.com/JetBrains/intellij-community/blob/idea/232.10335.12/platform/vcs-api/src/com/intellij/openapi/vcs/VcsRootChecker.java)
明确把已有显式映射的目录放入 `mappedDirs`，并要求它们不参与自动检测；
这与现有“显式映射容器，再返回其中多个根”的功能不等价。
目前没有证明公开替代可以同时保持容器设置、完整快照发布、取消及 232 dirty-scope 行为。

`filterUniqueRoots()` 当前直接返回输入，避免丢弃嵌套根。两代 AbstractVcs 默认实现都
在 `allowsNestedRoots()` 为 false 时过滤后代。公开覆写 `allowsNestedRoots()` 返回 true
可保留这一方法的局部结果，但它还会影响平台其他调用方，且不能解决根转换器问题。
本轮不部分切换这一族，不修改 `CvsVcs2` 或 `CvsRootDiscovery`。

下一步可选方案：

1. **先向 JetBrains 申请兼容性例外或公开扩展点建议。** 提供剩余两处报告与上述
   容器/嵌套根复现说明，说明它们服务于 232–262 兼容；不要声称已经证明误报。
   本轮只整理材料，没有发送回复。
2. **另行批准根映射迁移。** 调查 `VcsRootChecker`、显式子根映射与公开
   `allowsNestedRoots()`，在用户确认设置迁移/行为取舍后实施。必须完成下述两代 IDE
   真机验收，不能仅删除覆写或以静态 verifier 通过代替验收。

在以上决策之前，不改版本、不 prepare、不移除剩余覆写。

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
