export type ProjectStatus = 'RUNNING' | 'ATTACHED' | 'EXTERNAL' | 'STOPPED' | 'ERROR'

export type ProjectCategory = 'APPLICATION' | 'DATABASE' | 'SCRIPT' | 'OTHER'

export interface ProjectDto {
  id: string
  name: string
  rootDirectory: string
  startCommand: string
  stopCommand?: string | null
  ports: number[]
  description?: string | null
  category: ProjectCategory
  sortOrder: number
  createdAt: string
  updatedAt: string
  status: ProjectStatus
  pid?: number | null
  startedAt?: string | null
  detectedPorts?: number[] | null
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
  remoteUrl?: string | null
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

export interface ProjectFormValues {
  name: string
  rootDirectory: string
  startCommand: string
  stopCommand: string
  ports: string // comma separated in form
  description: string
}
