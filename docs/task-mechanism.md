# Task 机制（intellij-cvs-plugin）

本仓是总仓 `host/config/workspaces.json` 中登记 id `intellij-cvs-plugin` 的项目根。工程任务分两层，互不冒充终态：

## 1. 总仓 Task（机器状态）

- 入口：在总仓根运行 `host/bin/universe task <list|show|create|update|continue|finish|cancel>`；按登记 id 定位本仓，不在本仓写死总仓或机器路径。
- Task 状态不作为本插件的发布门；GTD 勾选、Jira 状态都不签发 done。

## 2. 本仓里程碑

- 人类可读进度在 `README.md` 的 Status 与 `README_ZH.md` 的「状态」，两份同步维护。
- 技术合同在 `AGENTS.md`（P-01 保原码、P-02 解耦、P-03 i18n、P-04 目标 IDE）。
- 独立 Git 根：`https://github.com/jieyuexing/intellij-cvs-plugin`，提交与 push 以本仓 remote 为准。

## 3. 新建 Task 的建议内容

1. **outcome**：一句话现实结果
2. **done_when**：可验证条件（命令或文件证据）
3. **non_goals**：明确不做
4. **write_scope**：精确到文件
5. **verify**：至少一条真实证据
6. 主目标 IDE 变更时同步更新 `gradle.properties` 版本主号（与 IDEA 平台线对齐，如 `262.x`）

## 4. i18n 验收

在本仓根执行：

```bash
python3 scripts/check_i18n_keys.py
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew buildPlugin
# zip 内应含 messages/CvsBundle_zh.properties
```

IDEA 界面语言为中文时，设置 → 版本控制 → CVS / Global Settings 应显示中文（`DynamicBundle` 自动按 Locale 加载 `_zh`）。
