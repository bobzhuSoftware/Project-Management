# PROD 打包追踪（构建记录 + 过期提示）实施方案

## 背景

PROD 的使用流程是：在项目 Edit 里配置一个打包命令（Project Command，例如 `build-prod.cmd`，通常勾选 Script），从 `⋯` 菜单运行它打包；然后用 PROD launch（例如 `start-prod`）启动打包产物。

问题：pull 了新代码之后，如果忘记重新打包，PROD 仍在使用旧产物，界面上没有任何提示。

现状（已核实）：

- `ProjectCommand`（[ProjectCommand.java](../../backend/src/main/java/com/pm/project/ProjectCommand.java)）与 `Launch` 之间没有任何关联，系统不知道哪个命令是“PROD 的打包命令”。
- 命令执行（[ProcessSupervisor.java](../../backend/src/main/java/com/pm/process/ProcessSupervisor.java) 的 `runCommand` / `runCommandAsync`）不记录任何历史；script 命令的退出码没人读取。
- `GitStatusDto` 只在 detached 时返回 `headShort`，完整 HEAD SHA 不对外暴露，也不持久化。
- 命令 id 在编辑保存时保持稳定（`reconcileCommands` 按 id 复用），可以安全地被引用。

## 目标与范围

**目标**：PM 能告诉我“这个 PROD 上次打包用的提交 ≠ 当前 HEAD，需要重新打包”，并能看到最近几次打包记录。

**范围内**

1. 把打包命令与目标 launch 关联起来（在 Edit 里配置）。
2. 只为“已关联到 launch 的打包命令”写操作记录（其他命令、start/stop、git 操作都不记录）。
3. 记录内容：开始时的 HEAD 提交、开始/结束时间、结果（成功/失败/中断）、退出码。
4. 在 launch 行上显示过期徽标；点击查看该 launch 的打包历史。
5. Pull 成功后，如果有 launch 因此过期，在结果里提示。

**范围外（已与用户确认）**

- 不比较工作区未提交改动，只比较已提交的 HEAD。
- 不提示“已打包但进程未重启”。
- 不检测 PM 外部（终端/IDE）的手动打包，也不提供“手动标记为已打包”。
- 不自动触发打包。

## 设计决策

| 问题 | 决策 | 理由 |
|---|---|---|
| 如何关联命令与 launch | `ProjectCommand` 新增可空字段 `buildForLaunchId` | 一对一配置简单；不依赖 launch 名字叫 “PROD” |
| 多个命令关联同一 launch（如前端+后端分别打包） | 允许。**任一**关联命令的最后成功提交 ≠ HEAD 即视为过期 | 分段打包时任何一段旧了，产物都是旧的 |
| 记录哪个提交 | 命令**开始时**的 HEAD | 打包读取的是开始时的代码 |
| 失败的打包 | 记录，但不更新“最后成功提交” | 历史里能看到失败；判定只看成功 |
| PM 在打包过程中退出 | 启动时把遗留的 `RUNNING` 记录改为 `INTERRUPTED` | 避免永久“打包中” |
| 从没打包过（含功能上线前已有的 PROD） | 显示灰色“无打包记录”，不显示为过期 | 不确定时不报警；跑一次打包即可建立基线 |
| 历史保留 | 每个 launch 只保留最近 20 条 | 足够排查，不膨胀 |
| 非 git 项目 / git 出错 | 不显示徽标 | 无法判断 |

## 数据模型

在 [schema.sql](../../backend/src/main/resources/schema.sql) 末尾追加（该文件用于给已有数据库做增量迁移），并同步到 [schema-create.sql](../../backend/src/main/resources/schema-create.sql)：

```sql
ALTER TABLE project_commands ADD COLUMN IF NOT EXISTS build_for_launch_id VARCHAR(36);

CREATE TABLE IF NOT EXISTS build_records (
    id            VARCHAR(36)   NOT NULL,
    project_id    VARCHAR(36)   NOT NULL,
    launch_id     VARCHAR(36)   NOT NULL,
    command_id    VARCHAR(36)   NOT NULL,
    command_name  VARCHAR(200)  NOT NULL,   -- 快照，命令改名/删除后历史仍可读
    commit_sha    VARCHAR(40),              -- 开始时的 HEAD；非 git 或读取失败为 NULL
    commit_subject VARCHAR(500),            -- 提交标题，便于在历史里辨认
    branch        VARCHAR(200),
    status        VARCHAR(16)   NOT NULL,   -- RUNNING / SUCCESS / FAILED / INTERRUPTED
    exit_code     INT,
    started_at    TIMESTAMP     NOT NULL,
    finished_at   TIMESTAMP,
    CONSTRAINT pk_build_records PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_build_records_launch ON build_records (launch_id, started_at);
```

不对 `launch_id` / `command_id` 加外键：删除 launch 时由代码清理；删除命令时保留历史。

## 后端

### 1. 实体与仓库（新包 `com.pm.build`）

- `BuildRecord` 实体 + `BuildStatus` 枚举（`RUNNING, SUCCESS, FAILED, INTERRUPTED`）。
- `BuildRecordRepository`：
  - `findTop20ByLaunchIdOrderByStartedAtDesc(launchId)`
  - `findFirstByCommandIdAndStatusOrderByStartedAtDesc(commandId, SUCCESS)`
  - `findByStatus(RUNNING)`（启动时清理用）
  - `deleteByLaunchId(launchId)`、`deleteByProjectId(projectId)`

### 2. `ProjectCommand` / DTO

- `ProjectCommand` 加 `buildForLaunchId`（`@Column(name = "build_for_launch_id", length = 36)`）。
- `ProjectCommandRequest` / `ProjectCommandResponse` 加 `buildForLaunchId`。
- `ProjectService.reconcileCommands` 保存该字段。校验：值必须是**本项目**的 launch id，否则 400。
  - 注意顺序：新建 launch 在请求里还没有 id。前端只允许选择已保存的 launch（见前端部分），后端对未知 id 返回 400 即可。
- `reconcileLaunches` 删除 launch 时：把引用它的命令的 `buildForLaunchId` 置空，并 `deleteByLaunchId`。
- 删除项目时 `deleteByProjectId`。

### 3. Git HEAD 读取

在 [GitService.java](../../backend/src/main/java/com/pm/git/GitService.java) 新增（用 JGit，不起进程，不走 30s 状态缓存）：

```java
/** 当前 HEAD 信息；非 git 仓库或无提交时返回 Optional.empty()。 */
Optional<HeadInfo> readHead(Project p);              // sha, subject, branch

/** base..head 的提交数；base 不是 head 的祖先（切分支/重写历史）时返回 -1。 */
int countCommitsBetween(Project p, String baseSha, String headSha);
```

`countCommitsBetween` 用 `RevWalk`：`markStart(head)`、`markUninteresting(base)` 计数；先用 `RevWalkUtils`/`isMergedInto` 判断祖先关系。

### 4. 记录打包（`BuildRecordService`）

在 `ProjectService.runCommand` 中（目前是 `@Transactional(readOnly = true)`，需要改掉或把写操作放到独立的 `BuildRecordService` 方法里，各自开事务）：

1. 若 `cmd.getBuildForLaunchId() == null` → 走原逻辑，**不记录**。
2. 否则调用 `BuildRecordService.begin(project, cmd)`：读取 HEAD，插入 `RUNNING` 记录，返回 record id。
3. 执行命令：
   - **同步命令**：`supervisor.runCommand` 正常返回 → `finish(id, SUCCESS, 0)`；抛出“failed (exit N)” → `FAILED, N`；超时或其他异常 → `FAILED, null`。异常继续向上抛，保持现有行为。
   - **Script 命令**：`runCommandAsync` 需要能回调退出码。建议给它加一个可选参数 `IntConsumer onExit`，在已有的 `p.onExit().thenRun(...)` 处调用 `onExit.accept(p.exitValue())`；`BuildRecordService` 据此 `finish(id, exit == 0 ? SUCCESS : FAILED, exit)`。启动失败（`pb.start()` 抛异常）→ `FAILED`。
4. `finish` 后裁剪该 launch 的记录，只保留最近 20 条。
5. `ApplicationReadyEvent` 时把所有 `RUNNING` 记录改为 `INTERRUPTED`。

### 5. 过期判定（`BuildFreshness`）

对每个 launch，找出 `buildForLaunchId == launch.id` 的命令集合：

- 集合为空 → `null`（前端不显示徽标）。
- 读不到 HEAD → `null`。
- 任一命令没有成功记录 → `state = NO_RECORD`。
- 任一命令的最后成功 `commit_sha ≠ HEAD` → `state = STALE`，`commitsBehind = countCommitsBetween(...)`（取各命令中的最大值；有 -1 时整体为 -1）。
- 否则 `state = FRESH`。
- 任一关联命令当前正在运行（有 `RUNNING` 记录）→ `state = BUILDING`（优先级最高）。

返回 DTO：

```java
public class BuildFreshnessDto {
    public String state;            // FRESH / STALE / NO_RECORD / BUILDING
    public Integer commitsBehind;   // 仅 STALE；-1 表示不在同一历史线上
    public String headSha;          // 当前 HEAD（短 SHA 即可）
    public String builtSha;         // 最近一次成功打包的提交（多命令时取最旧的那个）
    public String builtSubject;
    public Instant builtAt;
    public String lastResult;       // 最近一条记录的 status，便于显示“上次打包失败”
}
```

放在 `LaunchResponse` 的新字段 `build` 里。**性能**：项目列表每 3 秒轮询一次，所以：

- `readHead` 用 JGit 读 ref，开销很小；
- `countCommitsBetween` 结果按 `(projectId, builtSha, headSha)` 缓存（`ConcurrentHashMap`，key 不变则结果不变，无需 TTL）；
- 一个项目的多个 launch 共享同一次 `readHead`。

### 6. 接口

- `GET /api/launches/{id}/builds` → 最近 20 条 `BuildRecordDto`（id, commandName, commitSha, commitSubject, branch, status, exitCode, startedAt, finishedAt）。
- `GitSyncResultDto` 不改；pull 后的提示由前端根据刷新后的项目列表判断（见下）。

## 前端

### 1. 类型与 API（[types.ts](../../frontend/src/types.ts)、[api.ts](../../frontend/src/api.ts)）

- `ProjectCommandDto` / 表单值 / payload 加 `buildForLaunchId?: string | null`。
- `LaunchDto` 加 `build?: BuildFreshnessDto | null`。
- 新增 `BuildRecordDto` 与 `launchesApi.builds(launchId)`。

### 2. Edit 弹窗（[ProjectFormModal.tsx](../../frontend/src/components/ProjectFormModal.tsx)）

在每个命令的编辑区（现有 “Require stopped” / “Script” 勾选项附近）加一个下拉：

> **Builds for launch**：`— (not a build command)` / `DEV` / `PROD` / …

- 选项只列出**已保存**（有 id）的 launch；未保存的 launch 不出现，下方提示“保存后才能选择新建的启动项”。
- 选中后说明文字：“运行此命令会被记录为该启动项的打包，用于判断是否需要重新打包。”

### 3. Launch 行徽标（[ProjectTable.tsx](../../frontend/src/components/ProjectTable.tsx)）

在 launch 子行（截图中 `DEV` / `PROD` 那一行）名称旁显示：

| state | 文本 | 样式 |
|---|---|---|
| STALE, commitsBehind > 0 | `⚠ 需重新打包 ↓N` | 橙色（复用 `git-badge warn`） |
| STALE, commitsBehind = -1 | `⚠ 需重新打包（提交不同）` | 橙色 |
| NO_RECORD | `无打包记录` | 灰色（`git-badge none`） |
| BUILDING | `打包中…` | 蓝色 |
| FRESH | `✓ 已是最新` | 绿色，小号；或直接不显示（实现时取前者，便于确认功能在工作） |
| 上次失败 且 STALE/NO_RECORD | 文本后加 ` · 上次失败` | 同上 |

- `title`（悬停）：`当前 HEAD abc1234 · 打包于 def5678 "提交标题" · 2026-11-11 14:02`。
- 点击徽标 → 打开打包历史弹窗。
- 单 launch 项目（没有展开子行、launch 直接显示在项目行上）同样显示在名称旁。

### 4. 打包历史弹窗（新组件 `BuildHistoryModal.tsx`）

- 标题：`<项目名> / <launch 名> 打包记录`。
- 表格列：时间、命令、提交（短 SHA + 标题）、分支、结果（成功/失败/中断/进行中 + 退出码）、耗时。
- 当前 HEAD 对应的成功记录高亮。
- 底部按钮：对每个关联的打包命令提供 “运行 <命令名>”（复用 `onRunCommand`，script 命令会照常打开日志抽屉）。

### 5. Pull 后提示（[GitSyncModal.tsx](../../frontend/src/components/GitSyncModal.tsx) / [App.tsx](../../frontend/src/App.tsx)）

Pull 成功后，`App` 立即 `refresh()` 项目列表；若该项目有 launch 的 `build.state === 'STALE'`，在 pull 结果区域显示：

> 代码已更新。以下启动项需要重新打包：PROD　[运行 Build prod]

按钮直接运行对应打包命令。若 PROD 正在运行且命令 `requireStopped`，按钮禁用并提示先停止（复用现有禁用逻辑）。

## 实施步骤

1. **数据层**：schema 迁移、`BuildRecord` 实体/仓库、`ProjectCommand.buildForLaunchId` 及 DTO、`reconcileCommands` 校验、删除 launch/项目时的清理、启动时 `RUNNING → INTERRUPTED`。
2. **Git**：`readHead`、`countCommitsBetween` + 缓存。
3. **记录**：`BuildRecordService` + `runCommand` 接入；`runCommandAsync` 增加退出回调。
4. **判定与接口**：`BuildFreshnessDto` 接入 `LaunchResponse`；`GET /api/launches/{id}/builds`。
5. **前端**：类型/API → Edit 下拉 → 徽标 → 历史弹窗 → pull 后提示。

## 验证

在**临时副本**上验证，不动用户正在运行的实例和真实数据库（端口、H2 路径、日志目录都指向临时目录）。用一个脚本创建的临时 git 仓库作为测试项目（带 bare 远程），项目配置：

- launch `PROD`：`echo prod`（不需要真正启动）；
- 命令 `Build prod`：`cmd /c exit 0`，关联 `PROD`，分别测 Script 与非 Script；
- 命令 `Build fail`：`cmd /c exit 3`，关联 `PROD`（只在失败用例里启用）；
- 命令 `Clean`：不关联。

用例：

| # | 操作 | 预期 |
|---|---|---|
| 1 | 新建后不打包 | PROD 显示“无打包记录”；DEV（未关联）无徽标 |
| 2 | 运行 Build prod | 记录 SUCCESS，sha = HEAD；徽标“已是最新” |
| 3 | 远程推 2 个提交，PM 里 pull | 徽标“需重新打包 ↓2”；pull 结果出现提示和运行按钮 |
| 4 | 再次运行 Build prod | 回到“已是最新” |
| 5 | 运行 Build fail | 记录 FAILED, exitCode=3；判定仍基于最后一次成功 |
| 6 | 运行 Clean | 不产生任何记录 |
| 7 | Script 打包运行中查看 | 徽标“打包中…”；结束后按退出码更新 |
| 8 | 打包中停止 PM 再启动 | 该记录变为 INTERRUPTED |
| 9 | 切到另一条无关分支 | “需重新打包（提交不同）” |
| 10 | 只修改工作区不提交 | 判定不变 |
| 11 | 连续打包 25 次 | 历史只保留 20 条 |
| 12 | 删除 PROD launch | 命令的关联被清空，记录被删除 |
| 13 | 编辑命令把关联指向另一项目的 launch id（直接调 API） | 400 |

最后在浏览器里实际走一遍 3、4 两步，确认徽标随 3 秒轮询更新。
