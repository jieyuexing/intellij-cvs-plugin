# Task 机制（intellij-cvs-plugin）

本 fork 的工程任务有两层，**互不冒充终态**：

## 1. harness-universe Task Store（机器状态权威）

| 项 | 值 |
| --- | --- |
| Authority | `planet-resources/tasks/store.json` |
| CLI | `planet-extensions/task-store/bin/task-store` |
| 生命周期 | `plan` → `start` → (`transition`) → `verify` → `close` |
| 约束 | revision CAS、幂等 `event_id`、精确 `write_scope`、证据门 |

### 本插件相关 Task 示例

| task_id | work_object_id | 用途 |
| --- | --- | --- |
| `task-intellij-cvs-i18n-zh-v1` | `harness://work-objects/intellij-cvs-i18n-zh` | en+zh 文案与 IDEA 语言自动切换 |

常用命令：

```bash
# 查看
planet-extensions/task-store/bin/task-store show task-intellij-cvs-i18n-zh-v1

# plan / start / verify / close 见 framework-task-lifecycle-governance
```

**GTD 勾选 ≠ Task done**；Jira 状态也不签发 done。

## 2. 本仓库（GitHub）里程碑清单

人类可读进度放在 `README.md` / `README_ZH.md` 的 Status 列表；  
技术合同在 `AGENTS.md`（P-01 保原码、P-02 解耦、P-03 i18n、P-04 目标 IDE）。

独立 Git 根：`https://github.com/jieyuexing/intellij-cvs-plugin`  
（可寄宿在 harness 的 `planet-resources/planet-projects/intellij-plugins/intellij-cvs-plugin`，提交/push 以本仓 remote 为准。）

## 3. 新建 Task 的建议模板

1. **outcome**：一句话现实结果  
2. **done_when**：可验证条件（命令/文件证据）  
3. **non_goals**：明确不做  
4. **write_scope**：精确到文件  
5. **verify**：至少一条真实 evidence_ref  
6. 主目标 IDE 变更时同步更新 `gradle.properties` 版本主号（与 IDEA 平台线对齐，如 `262.x`）

## 4. i18n Task 验收

```bash
cd planet-resources/planet-projects/intellij-plugins/intellij-cvs-plugin
python3 scripts/check_i18n_keys.py
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew buildPlugin
# zip 内应含 messages/CvsBundle_zh.properties
```

IDEA 界面语言为中文时，设置 → 版本控制 → CVS / Global Settings 应显示中文（`DynamicBundle` 自动按 Locale 加载 `_zh`）。
