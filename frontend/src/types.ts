export type ProjectStatus = 'RUNNING' | 'ATTACHED' | 'EXTERNAL' | 'STOPPED' | 'ERROR'

export interface ProjectDto {
  id: string
  name: string
  rootDirectory: string
  startCommand: string
  stopCommand?: string | null
  ports: number[]
  description?: string | null
  sortOrder: number
  createdAt: string
  updatedAt: string
  status: ProjectStatus
  pid?: number | null
  startedAt?: string | null
  detectedPorts?: number[] | null
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
  checkedAt: string
  error?: string | null
}

export interface GitSyncResultDto {
  success: boolean
  message?: string | null
  steps: string[]
  status: GitStatusDto
}

export interface ProjectFormValues {
  name: string
  rootDirectory: string
  startCommand: string
  stopCommand: string
  ports: string // comma separated in form
  description: string
}
