# Command 可停止 / 防卡死 实施记录

## 背景

项目 `⋯` 菜单里的 Command（例如 `npm install`、`build-prod.cmd`）一旦跑得很久或卡住，用户没有任何办法在中途停掉，只能重启 PM 或手动 `taskkill`。

## 根因（已核实）

1. **Script 命令没有停止入口。** `runCommandAsync` 启动后只登记到 `live`，后端没有 stop 接口，前端日志抽屉里的 "Pause" 只是暂停滚动。
2. **子进程 stdin 没处理。** 命令里有交互（`pause`、y/n、登录提示）时子进程一直等输入；script 又没有超时，会永远卡住。
3. **同步命令超时只杀根进程。** `runCommand` 超时后只 `destroyForcibly()` PowerShell，`cmd` / `mvn` / `node` 子进程成为孤儿，继续占文件锁。
4. **`runCommand` 是 `synchronized`。** 与 `start` / `stop` / `runCommandAsync` 共用 supervisor 锁，一个最长 120 秒的同步命令会让所有项目的 Start / Stop 排队。
5. **前端看不到命令在运行。** 命令只在 `⋯` 菜单里；同步命令执行期间整个项目行被 `busyId` 锁住，菜单打不开，也无法取消。
6. **构建记录没有"被取消"语义。** 进程被杀后退出码非 0，会被记成 `FAILED`，分不清是构建出错还是人为中止。

## 方案与实现

### 后端

| 改动 | 位置 |
|---|---|
| `killTree(pid)`：先两轮快照杀后代，再杀根；根 2 秒内没退出则 `taskkill /PID x /T /F` 兜底。launch 的 `stop`、命令超时、命令取消都复用 | [ProcessSupervisor.java](../../backend/src/main/java/com/pm/process/ProcessSupervisor.java) |
| `stopCommand(commandId)`：对 script 标记 `cancelRequested`、写 `[pm] stop requested…` 到日志、杀进程树；对同步命令标记 `cancelled` 并杀进程树。**不加 `synchronized`**，不会排在别的长操作后面 | 同上 |
| 同步命令登记到 `syncRuns`，`runCommand` 去掉 `synchronized`；同一命令重复运行直接报 "already running" | 同上 |
| 命令进程 stdin 绑定到 `NUL`（`commandProcessBuilder`），遇到 `pause` / 交互提示立即得到 EOF | 同上 |
| Script 的 `timeoutSeconds` 生效：留空 = 不限时（与旧行为一致），填了 = 最大运行时间，超时由守护线程杀进程树并写 `[pm] timed out after Ns…` | 同上 |
| `runCommandAsync` 的回调由 `IntConsumer` 改为 `Consumer<CommandExit>`（`exitCode` / `cancelled` / `timedOut`） | 同上 |
| `ManagedProcess` 新增 `lastOutputAt`、`cancelRequested`、`timedOut`、`note()`（向日志缓冲、SSE、日志文件写 `[pm]` 行）；日志文件写入改为带锁，便于 `note()` 并发写 | [ManagedProcess.java](../../backend/src/main/java/com/pm/process/ManagedProcess.java) |
| 新增 `CommandCancelledException`、`CommandTimeoutException`（均继承 `IllegalStateException`，仍映射 HTTP 409） | `com.pm.process` |
| `BuildStatus` 新增 `CANCELLED`、`TIMED_OUT`。**注意：** 旧库里 `build_records.status` 带 Hibernate 生成的 CHECK 约束，需要迁移，见下方"Bug 1" | [BuildStatus.java](../../backend/src/main/java/com/pm/build/BuildStatus.java) |
| `runBuildCommand`：重复运行检查前移到记录创建之前（同步命令也检查）；按结果记为 `SUCCESS` / `FAILED` / `CANCELLED` / `TIMED_OUT` | [ProjectService.java](../../backend/src/main/java/com/pm/project/ProjectService.java) |
| 新接口 `POST /api/projects/{id}/commands/{commandId}/stop`，返回是否真的有东西被停止 | [ProjectController.java](../../backend/src/main/java/com/pm/project/ProjectController.java) |
| `ProjectCommandResponse` 新增 `running` / `startedAt` / `lastOutputAt`，随现有 3 秒轮询一起返回 | [ProjectCommandResponse.java](../../backend/src/main/java/com/pm/project/dto/ProjectCommandResponse.java) |

### 前端

- **日志抽屉**（`kind === 'command'`）：运行中显示红色 **■ Stop**（点击前确认）；新增状态行 `Running · 12m 30s · last output 5m ago`；超过 2 分钟无输出时变黄并提示"可能卡住，可用 Stop 杀掉"。原 `Pause` / `Resume` 改名 `Pause scroll` / `Resume scroll`。
- **`⋯` 菜单**：运行中的命令（script 或同步）显示 `■ Stop <name>`；`⋯` 按钮不再因为项目忙而禁用，运行中时带绿色闪烁圆点；Delete 在有命令运行时禁用。
- **构建历史弹窗**：运行中的构建命令按钮变为红色 `■ Stop`。
- **取消同步命令**：被用户停止的同步命令不再弹错误对话框。
- **表单**：Script 的超时输入框不再禁用，标签为 "Max run time seconds (optional, blank = unlimited)"。
- **构建状态**：徽标、历史、样式新增 "Cancelled"（琥珀色）/ "Timed out"（红色）。
- 涉及文件：`App.tsx`、`api.ts`、`types.ts`、`LogsDrawer.tsx`、`ProjectTable.tsx`、`BuildHistoryModal.tsx`、`BuildBadge.tsx`、`ProjectFormModal.tsx`、`styles.css`。

## 设计取舍

- **stdin 不对 launch 生效。** 只对 Command（同步 / script）重定向到 `NUL`。launch 保持原样，因为部分开发服务器（如 Vite）在 stdin 关闭时会自行退出。
- **"无输出"只提示，不自动杀。** `npm install` 之类本来就可能长时间没输出，自动杀容易误伤；是否停止由用户决定。
- **Script 超时留空 = 不限时。** 保持旧行为，不会让现有配置突然被限时。
- **取消复用同一套 `killTree`。** 取消、超时、launch 停止行为一致，避免三套杀进程逻辑各有遗漏。

## 验证

用临时数据库和端口（18090）启动改动后的后端，通过 REST 实测，结果均符合预期：

| 场景 | 结果 |
|---|---|
| Script `ping -n 600` 运行中调用 stop | `running` 变 false，`ping` 进程 0 个，构建记录 `CANCELLED`；再次 stop 返回 `false` |
| Script `pause`（stdin 为 NUL） | 约 4 秒内自行结束，不再卡住 |
| 同步命令 `timeoutSeconds=5` | 恰好 5 秒后返回，`ping` 进程 0 个（子进程一并被杀） |
| 同步命令运行中调用 stop | `running=true` 期间可被停止，原 run 请求返回，`ping` 进程 0 个，构建记录 `CANCELLED` |
| Script `timeoutSeconds=6` | 约 6 秒后被杀，日志写入 `[pm] timed out after 6s`，无残留进程 |

另：`mvn compile`（JDK 17）与前端 `tsc --noEmit` 均通过。仓库目前没有测试代码，故未新增自动化测试。

## 已知限制 / 后续可做

- 前端 axios 默认超时 30 秒（[api.ts](../../frontend/src/api.ts)），同步命令超过 30 秒时前端会先报超时，但服务端仍在跑；现在能在菜单里看到 "running" 并 Stop。长任务建议勾选 Script。
- 历史遗留：若某个 Script 命令的 `timeout_seconds` 字段里残留旧值（之前 UI 禁用了输入但不会清空），升级后该值会作为最大运行时间生效，需要时在 Edit 里清空。
- 日志 SSE 在进程结束后，浏览器 `EventSource` 会自动重连并再次回放日志文件尾部（原有行为，未改动）。
- `TIMED_OUT` 的构建记录路径（构建命令超时）与同步命令超时路径逻辑简单，未单独实测；Script 超时、取消两条已实测。

---

## 后续 Bug 1：点 Stop 后关联的 build 启动项仍显示 "Building…"

### 现象

停止一个关联到 launch 的 Script 构建命令（如 `Build Prod`）后，PROD 行的徽标一直是 "Building…"（上线后第一次实测发现）。

### 根因

后端日志（16:05:25）：

```
Check constraint violation: "CONSTRAINT_6"
update build_records set ... status=? where id=?
Could not finish build record 2103ff3f-…
```

- 旧数据库里的 `build_records.status` 带一条 Hibernate 在建表时按当时枚举自动生成的 CHECK 约束：`"STATUS" IN('RUNNING','SUCCESS','FAILED','INTERRUPTED')`。
- 新增的 `CANCELLED` / `TIMED_OUT` 写入时被数据库拒绝。
- `BuildRecordService.finish()` 按设计吞掉异常（记录失败不能影响构建本身），记录因此永远停在 `RUNNING`，界面持续显示 Building。
- 首次验证用的是全新的临时库，其约束已包含新枚举值，所以没有暴露。教训：涉及枚举/表结构的改动要在**带旧结构的库**上验证。

### 修复

| 层 | 改动 |
|---|---|
| 迁移（治旧库） | 新增 [BuildStatusConstraintMigration.java](../../backend/src/main/java/com/pm/build/BuildStatusConstraintMigration.java)：启动时在 `INFORMATION_SCHEMA` 里按"表为 `build_records` 的 CHECK 约束且条件含 `STATUS`"查出约束（名字是自动生成的 `CONSTRAINT_n`，不能写死），执行 `ALTER TABLE … DROP CONSTRAINT`。幂等，无约束时什么都不做 |
| 实体（治新库） | [BuildRecord.java](../../backend/src/main/java/com/pm/build/BuildRecord.java) 的 `status` 加 `columnDefinition = "VARCHAR(16)"`，Hibernate 不再为它生成 CHECK 约束，以后再加枚举值不会重演 |
| 自愈（兜底） | [BuildRecordService.java](../../backend/src/main/java/com/pm/build/BuildRecordService.java) 的 `freshness` 增加 `commandRunning` 判断：库里是 `RUNNING` 但命令进程已不存在（且记录开始已超过 15 秒）时，不再显示 Building，并在独立事务（`REQUIRES_NEW`，因为调用方可能是只读请求）里把记录改成 `INTERRUPTED`。15 秒宽限是因为记录先于进程创建，避免误判刚启动的构建 |
| 日志 | `finish()` 失败由 WARN 改为 ERROR，并带上目标状态 |

### 验证

1. **真实库**：改动编译后 devtools 自动重启真实后端，日志出现 `Dropped outdated check constraint PUBLIC.CONSTRAINT_6 on build_records.status`；再查 `INFORMATION_SCHEMA`，该 CHECK 约束已消失，只剩主键。之前卡住的那条记录已在重启时被改为 `INTERRUPTED`。
2. **复现旧结构**：用库副本手工建出带原样 CHECK 约束的 `build_records`，启动改动后的后端：构建运行中 `state=BUILDING`；Stop 后记录为 `CANCELLED`，不再显示 Building。
3. **自愈**：直接向库里插入两条 `RUNNING` 记录（一条 5 分钟前、一条刚创建），宽限期内仍为 BUILDING，超过 15 秒后两条都变 `INTERRUPTED`，状态恢复正常。
4. **新库**：全新数据库建出的 `build_records` 只有主键，没有 CHECK 约束。

---

## 分析记录：构建"成功 / 失败"是如何判断的

> 仅为分析记录，本次**未做**改动。

### 判定依据：进程退出码

PM 不解析日志，只看退出码。实际执行的是：

```
powershell -Command "... & cmd.exe /c '<命令>'; exit $LASTEXITCODE"
```

因此拿到的是 `cmd /c` 的退出码。

| 类型 | 结果 | 记录状态 |
|---|---|---|
| Script（后台） | 退出码 = 0 | `SUCCESS` |
| Script（后台） | 退出码 ≠ 0 | `FAILED`（带退出码） |
| Script（后台） | 用户点 Stop | `CANCELLED` |
| Script（后台） | 超过"最大运行时间" | `TIMED_OUT` |
| 同步命令 | 退出码 = 0 / ≠ 0 | `SUCCESS` / `FAILED` |
| 同步命令 | 超时 / 被取消 | `TIMED_OUT` / `CANCELLED` |
| PM 重启时仍为 `RUNNING` 的记录 | — | `INTERRUPTED` |
| 命令根本没能启动 | — | `FAILED`（无退出码） |

只有关联了 launch 的命令才会写构建记录；记录的提交是**构建开始时**的 HEAD。

### "是否过期"如何判断

对每个关联命令取最近一次 `SUCCESS` 记录的提交，与当前 HEAD 比较：

- 全部一致 → `FRESH`（Up to date）；任一不同 → `STALE`（Rebuild needed，附落后提交数）。
- 任一命令没有成功记录 → `NO_RECORD`。
- 有 `RUNNING` 记录（且进程真的在跑）→ `BUILDING`。
- 失败 / 取消 / 超时 **不更新**基线，只会在徽标上追加 "last build failed / cancelled / timed out"。

### 当前项目的可靠性

`weekly-task-scrum` 的 `build-prod.cmd` 只有一行 `powershell -File build-prod.ps1`，脚本里每一步都检查 `$LASTEXITCODE` 并在失败时 `throw`（`-File` 下未捕获的 `throw` 退出码为 1），退出码能正确传回，判定可靠。

### 弱点（待后续考虑）

1. **退出码不诚实就会误判。** 如果某个 `.cmd` 先跑了会失败的步骤、最后一行却是 `echo done` 或 `pause`，整体退出码为 0，会被记成 `SUCCESS`，并且更新"最后成功提交"基线，导致徽标显示 Up to date 但产物其实没构建出来。判定是否可靠取决于每个项目的脚本有没有把失败传回退出码。
2. **`pause` 现在返回 0。** 命令 stdin 已绑定到 `NUL`，`pause` 会立即通过，不再阻塞；这本身是期望行为，但也意味着脚本末尾的 `pause` 不会影响退出码。
3. **界面信息有限。** 构建历史里只有状态和退出码，看不到失败原因，需要去日志里找。

### 可考虑的改进（按性价比）

- 构建历史里每条记录提供"查看当次日志"入口（日志文件已按命令 id 和日期归档，可按开始/结束时间定位）。
- 对关联 launch 的构建命令，可选的"产物校验"：例如要求指定文件（jar、`dist/index.html`）的修改时间晚于构建开始，才记 `SUCCESS`；否则记 `FAILED` 并注明"产物未更新"。
- 在 Edit 表单提示："构建命令必须在失败时返回非 0 退出码"，并给出 `.cmd` 里使用 `exit /b %errorlevel%` 的示例。

