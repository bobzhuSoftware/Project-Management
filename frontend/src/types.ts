export type ProjectStatus = 'RUNNING' | 'ATTACHED' | 'EXTERNAL' | 'STOPPED' | 'ERROR'

export type ProjectCategory = 'APPLICATION' | 'DATABASE' | 'SCRIPT' | 'OTHER'

export type Reach = 'LOCAL' | 'WIFI' | 'INTERNET'

export type BuildState = 'FRESH' | 'STALE' | 'NO_RECORD' | 'BUILDING'

export type BuildStatus = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'INTERRUPTED' | 'CANCELLED' | 'TIMED_OUT'

export interface BuildFreshnessDto {
  state: BuildState
  /** Only for STALE; -1 means the built commit is not an ancestor of HEAD. */
  commitsBehind?: number | null
  headSha?: string | null
  builtSha?: string | null
  builtSubject?: string | null
  builtAt?: string | null
  lastResult?: BuildStatus | null
}

export interface BuildRecordDto {
  id: string
  commandName: string
  commitSha?: string | null
  commitSubject?: string | null
  branch?: string | null
  status: BuildStatus
  exitCode?: number | null
  startedAt: string
  finishedAt?: string | null
}

export interface LaunchDto {
  id: string
  projectId: string
  name: string
  alias?: string | null
  address?: string | null
  reach: Reach
  wifiAddress?: string | null
  shareUrl?: string | null
  shareKey?: string | null
  shareExpiresAt?: string | null
  startCommand: string
  stopCommand?: string | null
  ports: number[]
  sortOrder: number
  status: ProjectStatus
  pid?: number | null
  startedAt?: string | null
  detectedPorts?: number[] | null
  build?: BuildFreshnessDto | null
}

export interface ProjectDto {
  id: string
  name: string
  rootDirectory: string
  description?: string | null
  category: ProjectCategory
  sortOrder: number
  pushEnabled: boolean
  createdAt: string
  updatedAt: string
  launches: LaunchDto[]
  commands: ProjectCommandDto[]
}

export interface ProjectCommandDto {
  id: string
  projectId: string
  name: string
  command: string
  requireStopped: boolean
  script: boolean
  timeoutSeconds?: number | null
  buildForLaunchId?: string | null
  sortOrder: number
  /** True while the command's process is alive (script or synchronous). */
  running?: boolean
  startedAt?: string | null
  /** Last output of a running script; null/absent for synchronous commands. */
  lastOutputAt?: string | null
}

export type GitFileChangeType = 'ADDED' | 'MODIFIED' | 'DELETED' | 'RENAMED' | 'UNTRACKED' | 'CONFLICT'

export interface GitFileChange {
  path: string
  type: GitFileChangeType
  staged: boolean
}

export interface GitStatusDto {
  repo: boolean
  branch?: string | null
  detached?: boolean
  headShort?: string | null
  defaultBranch?: string | null
  remoteUrl?: string | null
  hasRemote?: boolean
  remoteBranchExists?: boolean
  upstreamName?: string | null
  hasUpstream: boolean
  ahead: number
  behind: number
  staged: number
  modified: number
  untracked: number
  conflicting: number
  clean: boolean
  inSync: boolean
  remoteChecked: boolean
  remoteError?: string | null
  checkedAt: string
  error?: string | null
  files: GitFileChange[]
}

export interface GitSyncResultDto {
  success: boolean
  message?: string | null
  steps: string[]
  status: GitStatusDto
}

export interface GitDiffDto {
  path: string
  staged: boolean
  binary: boolean
  truncated: boolean
  diff: string
}

export interface AppSettings {
  javaHome: string | null
  nodeHome: string | null
}

export interface LaunchFormValues {
  id?: string
  name: string
  startCommand: string
  stopCommand: string
  ports: string // comma separated in form
}

export interface ProjectFormValues {
  name: string
  rootDirectory: string
  description: string
  launches: LaunchFormValues[]
  commands: ProjectCommandFormValues[]
}

export interface ProjectCommandFormValues {
  id?: string
  name: string
  command: string
  requireStopped: boolean
  script: boolean
  timeoutSeconds: string
  buildForLaunchId: string
}
