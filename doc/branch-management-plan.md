# 分支管理 实施计划与 Roadmap

## 背景

当前 Git 状态只通过 `BranchTrackingStatus.of(repo, branch)` 判断 upstream。在本地新建或切换到没有跟踪关系的分支后，项目显示 `no upstream`，且：

- `sync()` 末尾执行裸 `git push`，在无 upstream 的分支上失败。
- `pull()` / `incoming()` 依赖 `@{u}`，不可用。
- 前端 `canSync` 要求 `hasUpstream`，Sync 被禁用。
- 界面不显示当前分支名，用户看不出项目已切到别的分支。

## 目标与范围

只做基础分支管理，不做完整的 Git 客户端。

**范围内**：显示当前分支、分支列表、切换、创建、发布（`push -u`）、无 upstream 状态的明确提示与处理；后续阶段加入 merge 与删除。
**范围外**：rebase、force push、stash 管理、冲突的可视化解决、tag、多 remote。

## 设计原则

1. 后端沿用 `GitService` 中的 `runGit`（调用系统 git，复用用户已有的凭据），状态读取沿用 JGit。
2. 所有来自客户端的分支名必须校验：白名单正则加 `git check-ref-format --branch`，拒绝以 `-` 开头，命令中使用 `--` 分隔。
3. 危险操作默认拦截而非自动处理：有未提交改动时不自动 stash 或丢弃；有冲突时不自动处理。
4. `pushEnabled` 开关对所有 push（包括 `push -u`）继续生效。
5. 每次分支操作后清除该项目的状态缓存并刷新。

## 阶段计划

### 阶段 0：现状确认与测试基线

- 梳理 `GitService.computeStatus`、`sync`、`pull` 以及前端 `renderGitBadge`、`canSync` 的所有 upstream 依赖点。
- 在临时仓库（脚本创建，非真实项目）里准备测试场景：无 upstream 的本地分支、远程已有同名分支但未跟踪、detached HEAD、无 remote。
- 产出：场景清单与可重复的验证脚本。

**状态：已完成。**

**验证脚本**：[scripts/git-scenarios.ps1](../scripts/git-scenarios.ps1)

- 在系统临时目录里创建 bare 远程仓库和 6 个场景仓库，结束后自动删除（`-Keep` 可保留）。
- 加 `-ApiBase http://localhost:8090` 时，会为每个场景注册一个临时项目（`pm-scenario-*`，`pushEnabled=false`，启动命令为 `echo noop` 且从不启动），读取 `/git/status` 并与预期比对，最后删除这些项目。
- 运行：`powershell -ExecutionPolicy Bypass -File <绝对路径>\scripts\git-scenarios.ps1 [-ApiBase ...] [-Keep]`。

**场景与当前行为（已实测）**

| 场景 | hasUpstream | 远程同名分支 | 有 remote | 当前后端返回的 branch |
|---|---|---|---|---|
| tracked-main | true | 是 | 是 | main |
| local-only-branch | false | 否 | 是 | feature-local |
| remote-same-name-untracked | false | 是 | 是 | feature-remote |
| published-tracked | true | 是 | 是 | feature-pub |
| detached-head | false | 否 | 是 | 提交 SHA（见下） |
| no-remote | false | 否 | 否 | main |

**审计结果：依赖 upstream 的位置**

后端 [GitService.java](../backend/src/main/java/com/pm/git/GitService.java)：

- `computeStatus`：`BranchTrackingStatus.of` 为 null 时 `hasUpstream=false`；`inSync` 要求 `hasUpstream`。
- `sync`：最后一步是裸 `git push`；`behind`/`ahead` 来自 upstream。
- `pull`：`!pre.hasUpstream` 直接失败；合并目标为 `@{u}`。
- `incoming` / `incomingDiff`：使用 `HEAD..@{u}`，无 upstream 时 git 报错。

前端：

- [ProjectTable.tsx](../frontend/src/components/ProjectTable.tsx) 的 `renderGitBadge`（`no upstream` 徽标）和 `canSync`。
- [GitSyncModal.tsx](../frontend/src/components/GitSyncModal.tsx) 的同步可用条件与 `(no upstream)` 文案。
- [types.ts](../frontend/src/types.ts) 的 `GitStatusDto.hasUpstream`；[App.tsx](../frontend/src/App.tsx) 里的默认状态对象。

**阶段 0 发现的问题（阶段 1 需处理）**

- detached HEAD 时，`repo.getBranch()` 返回的是提交 SHA，目前原样放进 `branch` 字段。阶段 1 要增加 `detached` 标志，并让 `branch` 为空，前端据此显示 `detached @ <短 SHA>`，不能把 SHA 当分支名使用（否则 `push -u origin <SHA>` 之类的操作会出错）。
- detached HEAD 当前被显示为 `no upstream`，需要单独的文案。
- 创建项目必须至少有一个启动配置（接口返回 400），测试脚本已按此处理。

### 阶段 1：分支信息可见（只读）

**状态：已完成。**

实际落地的字段与文案：`GitStatusDto` 新增 `detached`、`headShort`、`defaultBranch`、`hasRemote`、`remoteBranchExists`、`upstreamName`，detached 时 `branch` 为 null。界面徽标为 `unpublished`（纯本地分支）、`not tracking`（远程已有同名分支）、`no remote`、`detached HEAD`；项目行新增分支标签 `⎇ <branch>`，非默认分支或 detached 时高亮。Sync 按钮的可用条件在本阶段未改动，留给阶段 2。

- `GitStatusDto` 新增：`detached`（是否游离 HEAD）、`defaultBranch`、`upstreamName`、`remoteBranchExists`（`refs/remotes/origin/<branch>` 是否存在）、`hasRemote`。
- 前端项目行显示当前分支名；非默认分支或 detached 时加醒目标签。
- 徽标细分文案：
  - 远程已有同名分支但未跟踪：`未跟踪`
  - 纯本地分支：`未发布`
  - 没有 remote：`no remote`
- 此阶段不增加任何写操作，风险最低，同时能解决"看不懂 no upstream"的问题。

### 阶段 2：无 upstream 分支可同步

- `sync()`：检测到无 upstream 且有 remote 时，最后一步改为 `git push -u origin <branch>`；远程已有同名分支时只补跟踪关系（`git branch --set-upstream-to`），并先确认本地没有落后于远程。
- 前端：放宽 `canSync` 对 `hasUpstream` 的要求；Sync 弹窗明确写出"将把分支 `xxx` 发布到 origin"，需用户确认。
- `pull()` / `incoming()`：无 upstream 时返回明确的提示而不是 git 报错；前端相应禁用并说明原因。
- 遵守 `pushEnabled`。

### 阶段 3：分支列表、创建与切换

- 新接口（挂在 `/api/projects/{id}/git` 下）：
  - `GET /branches`：本地与远程分支列表，标记当前分支、是否有 upstream。
  - `POST /branches`：创建分支（可指定起点）并切换。
  - `POST /switch`：切换分支。
- 切换前置检查：
  - 有未提交改动或冲突：拒绝并说明。
  - 项目正在运行：返回警告，由用户确认后继续（不自动停止进程）。
- 前端新增 `BranchModal`：列表、搜索、切换、创建；入口为项目行的分支标签。
- 切换成功后刷新状态；若项目有关联的运行配置，提示用户可能需要重启。

### 阶段 4：Merge（含冲突处理边界）

- `POST /merge`：把指定分支 merge 到当前分支；默认先尝试 `--ff-only`，不能快进时才做普通 merge，并在界面中明确提示。
- 前置检查：工作区干净，当前分支与被合并分支都已更新到最新（先 fetch）。
- 冲突处理：工具只检测与提示，并提供"中止 merge"（`git merge --abort`）；冲突解决仍在 IDE 中完成，期间 Sync 保持被拦截（沿用现有逻辑）。
- merge 后若产生可推送提交，按阶段 2 的流程 Sync。

### 阶段 5：收尾功能（可选）

- 删除本地分支（`git branch -d`，不使用 `-D`；对当前分支和未合并分支拒绝）。
- 删除远程分支（需二次确认，遵守 `pushEnabled`）。
- 默认分支保护提醒：在 main/master 上执行 Sync 时给出可选的提示。

## 验证策略

- 每个阶段使用临时仓库（脚本创建并删除）做端到端验证，不改动真实项目数据。
- 重点用例：无 upstream 发布、同名远程分支、有改动时切换被拒、运行中项目切换警告、merge 冲突后的状态与中止、非法分支名（以 `-` 开头、含空格、`..`）被拒。
- 实际启动应用，在浏览器中走完整流程，不只依赖单元测试。

## Roadmap 与模型推荐

| 阶段 | 内容 | 风险 | 推荐模型 | 理由 |
|---|---|---|---|---|
| 0 | 现状确认、测试场景 | 低 | Claude Sonnet 5.5 | 读代码、写脚本，属于明确的执行型任务 |
| 1 | 分支信息可见、徽标细分 | 低 | Claude Sonnet 5.5 | 字段新增与前端展示，模式清晰，沿用现有结构 |
| 2 | 无 upstream 分支同步（`push -u`） | 中 | Claude Opus 4.8 | 涉及 push 逻辑与各种 git 边界状态（已有同名远程分支、落后于远程、无 remote），出错会写远程，需要更严谨的推演 |
| 3 | 分支列表、创建、切换 | 中 | Claude Sonnet 5.5 | 接口与界面工作量大但逻辑直接；其中的安全校验清单已在本文列明，按清单实现即可 |
| 4 | Merge 与冲突边界 | 高 | Claude Opus 4.8 | 状态机最复杂（ff-only、普通 merge、冲突、中止），需要对各种中间状态做完整推演并设计兜底 |
| 5 | 删除分支、默认分支提醒 | 低到中 | Claude Sonnet 5.5 | 范围小；删除类操作的保护规则已在计划中给出 |

**使用建议**

- 阶段 2 与阶段 4 的核心后端逻辑建议用 Opus 4.8 设计并实现，完成后可交给 Sonnet 5.5 补齐前端界面与测试脚本。
- 其余阶段使用 Sonnet 5.5 即可。
- 若阶段 1 到 3 完成后体验已满足需求，阶段 4、5 可以推迟。

## 建议的交付顺序

阶段 0 → 1 → 2（解决当前问题）→ 3（分支切换）→ 4 → 5。阶段 2 完成后即可独立发布，不依赖后续阶段。

## 待决定事项

- 无 upstream 的首次发布，是否每次都要弹窗确认（当前建议：是）。
- 切换分支时，对运行中的项目是仅警告还是直接阻止（当前建议：警告并允许继续）。
- merge 是否放进第一个发布版本（当前建议：不放）。
