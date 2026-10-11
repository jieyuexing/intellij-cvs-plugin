# Update 上传优化

文件复制未保留 mtime 时，CVS 原有时间戳判定会把未改内容也整份上传。
本实现包含独立的内容缓存和普通 update 分批；不改变版本、发布渠道或设置界面。

## 内容缓存

缓存键为规范化绝对路径、修订号、Entries 时间戳原串，值为文件大小与 SHA-256。
正常 Entries 写入后记录；Merged、冲突、Result of merge、新增/删除条目不记录。
时间戳一致的普通文件首次判定时播种，已有同键不重复计算。时间戳不一致时必须
键、大小和 SHA-256 全部匹配才发送 Unchanged，缺项或读取失败沿用 Modified。
缓存适用于所有经过 AdminReaderOnCache 的命令，包含状态检查和 `-n`；不改为 Is-modified。

命中后恢复**工作文件 mtime 到 Entries 时间**，不写 Entries。这样不需要另起一次
Entries 读改写，也不会覆盖响应线程或 CvsEntriesManager 中的其他条目。内容不改；
不能设置 mtime 时本次仍可根据已验证的哈希判为未修改，下次可能再次计算。
这项客户端元数据修复也可在 `-n` 中发生，但不改变服务器或工作文件内容。

缓存位于 IDE system 目录的 `opencvs/content-hashes-v1`，不在 CVS/ 或工作仓。
最多 10,000 条、磁盘文件最多 16 MiB，按访问顺序淘汰；命令结束时原子替换缓存文件。
缓存损坏、版本不符或 I/O 失败不阻断 CVS 命令。可删除缓存重建。
绝对路径变化、修订或 Entries 时间串变化即 miss。哈希前后检查文件属性，计算中支持取消。

保持 CVS 原有时间戳快捷判定，因此“内容变化但 mtime 未变化”的漏判边界仍然存在。
并发外部写入与 CVS 命令自身一样需要调用方避免；没有文件系统事务锁保证。

## 分批

UpdateOperation 默认阈值 20 MiB。只有不带 `-j/-C/-A/-r/-D/-k/-n` 的普通 update
启用分批；其他选项组合走原单次路径，内容缓存仍独立生效。

先使用原扫描器、忽略规则与修改判据（含哈希命中）估算将发送 Modified 的文件。
总量不超过阈值时直接执行原命令；超过时按扫描顺序去重、使用显式文件参数划批。
每批最多 20 MiB，单文件超大则独占一批。使用显式文件参数是为了拆开单个大目录内
的上传；目录 `-l` 无法满足这个目的。批次不带 `-d`，最终重新扫描并执行原整体
update，保留原选项，由它获取服务器新增文件和目录。真实本地修改或合并结果可能
在最后整体轮再次上传，并不保证最后一轮只有 Unchanged。

每轮使用新连接，避免复用已关闭并从 SSH 池注销的会话。批次前检查取消；取消、
连接/协议异常或非冲突失败不再发起后续轮次，已落地文件不回滚。
冲突不是提前结束其他文件处理的理由，后续批次与整体轮继续。

所有轮次共用 CvsMessagesTranslator 与 UpdatedFilesManager。前者按文件路径保存
结果，UpdatedFileInfo 保留合并/冲突信息，最后每文件只生成一次 FileMessage。
取消或后续异常也在 finally 中呈现已收到的结果。

## 冲突与错误

服务器 `error` 经 AbstractResponseHandler 发出 error 消息和失败终止事件。
普通 update 的 commandCompleted 不把返回 false 转成 CvsException；
CvsMessagesTranslator 对空消息或全空白消息直接返回。故整体轮的 `M C <file>`（或
`MT text/fname/newline`）加空白 `error`
本来就不追加用户错误，无需新增吞错规则。非空错误/警告仍走原消息翻译、
ErrorMessagesProcessor、CvsResult 与 UpdateSession 的路径。

等价判据是最终工作/备份内容、忽略时间戳的 Entries、去重后的更新结果分组及
用户错误/警告列表。批次判定复用 TaggedMessageParser，兼容普通 M 与分段 MT 冲突。
GNU 命令退出码或 javacvs 返回布尔值不单独作为等价判据。

## 验证与边界

离线测试入口位于 `testSource/com/intellij/cvsSupport2/`：

- `hash_cache_test.py`：真实缓存类的键、内容命中/失效、播种、持久化、容量、特殊条目、取消和 I/O 降级。
- `hash_cache_wiring_test.py`：运行真实 AdminReaderOnCache；基线在 mtime-only 行为断言处失败，候选恢复 mtime 且真修改保持 Modified。
- `upload_batch_planner_test.py` / `upload_batch_command_test.py`：规划、选项保护、M/MT 冲突继续、错误停止、取消、独立连接。
- `update_error_response_test.py`：真实 ResponseParser/AbstractResponseHandler 回放普通消息及本机录制的 MT/空格 error 序列。
- `upload_integration_test.py`：新建本机 CVS 1.12.13 仓，通过真实 server 进程、RequestProcessor、UpdateCommand、CvsMessagesTranslator、FileMessage、UpdatedFilesProcessor 比较五类样本；平台服务及 UpdatedFiles 容器是 stub，新条目记账模拟 AdminWriterOnCache。验证最终工作/备份内容、规范化 Entries 和 CREATED/MERGED/MERGED_WITH_CONFLICT/MODIFIED 分组一致、错误/警告列表一致；再验证已有冲突继续、非空错误/警告未吞掉、哈希命中 Unchanged 和真修改 Modified（含 `-n` 独立命中）。

先运行 `./gradlew buildPlugin`，再设置 `JAVA_HOME` 为本机 IDEA JBR、`TMPDIR/TMP/TEMP` 为任务临时目录，逐一运行以上 Python 脚本。
两个接线/集成测试使用构建 classpath，集成测试需 PATH 上的 GNU cvs（可设 `CVS_BINARY`），并把新建实验仓与协议日志保留在 TMPDIR。
`CVS_TEST_ROOT` 可指定基线快照；回放测试原行为已正确，基线与候选均通过，其余新功能有基线红、候选绿。
协议日志来自 IStreamLogger，控制帧完整但文件正文不经过该 logger，不将它声称为完整抓包。

构建、双语 key 校验和既有离线回归独立于 IDE 实机验收。

未在真实 pserver、公司工作副本或 IDEA 232/262 界面中运行；构建为 Java 17 字节码，
未改变安装范围。GNU CVS 1.11 显式文件参数可能逐文件 sleep，仍为已知未覆盖风险。
本机 1.12.13 小样本耗时不能代替 1.11 或真实大文件、网络吞吐验收。
