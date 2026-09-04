import axios from 'axios'
import type { AppSettings, GitDiffDto, GitFileChange, GitStatusDto, GitSyncResultDto, ProjectCategory, ProjectDto, Reach } from './types'

const api = axios.create({ baseURL: '/api', timeout: 30000 })

export interface LaunchPayload {
  id?: string
  name: string
  startCommand: string
  stopCommand?: string
  ports: number[]
}

export interface ProjectCommandPayload {
  id?: string
  name: string
  command: string
  requireStopped?: boolean
  script?: boolean
  timeoutSeconds?: number | null
}

export interface ProjectPayload {
  name: string
  rootDirectory: string
  description?: string
  category: ProjectCategory
  pushEnabled?: boolean
  launches: LaunchPayload[]
  commands: ProjectCommandPayload[]
}

export const projectsApi = {
  list: () => api.get<ProjectDto[]>('/projects').then(r => r.data),
  create: (p: ProjectPayload) => api.post<ProjectDto>('/projects', p).then(r => r.data),
  update: (id: string, p: ProjectPayload) => api.put<ProjectDto>(`/projects/${id}`, p).then(r => r.data),
  remove: (id: string) => api.delete(`/projects/${id}`),
  runCommand: (id: string, commandId: string) =>
    api.post<string>(`/projects/${id}/commands/${commandId}/run`).then(r => r.data),
  reorder: (orderedIds: string[]) => api.put('/projects/reorder', orderedIds),
  openFolder: (id: string) => api.post(`/projects/${id}/open-folder`),
  setPushEnabled: (id: string, enabled: boolean) =>
    api.put<ProjectDto>(`/projects/${id}/push-enabled`, { enabled }).then(r => r.data),
}

export const launchesApi = {
  start: (launchId: string) => api.post<ProjectDto>(`/launches/${launchId}/start`).then(r => r.data),
  stop: (launchId: string) => api.post<ProjectDto>(`/launches/${launchId}/stop`).then(r => r.data),
  setReach: (launchId: string, reach: Reach, shareTtlMinutes?: number | null) =>
    api.put<ProjectDto>(`/launches/${launchId}/reach`, { reach, shareTtlMinutes }).then(r => r.data),
}

export interface LogFileEntry {
  filename: string
  size: number
  modifiedAt: string
}

export const gitApi = {
  status: (id: string, refresh = false, checkRemote = false) =>
    api.get<GitStatusDto>(`/projects/${id}/git/status`, { params: { refresh, checkRemote } }).then(r => r.data),
  sync: (id: string, message: string) =>
    api.post<GitSyncResultDto>(`/projects/${id}/git/sync`, { message }).then(r => r.data),
  pull: (id: string, force = false) =>
    api.post<GitSyncResultDto>(`/projects/${id}/git/pull`, null, { params: { force } }).then(r => r.data),
  diff: (id: string, path: string, staged: boolean) =>
    api.get<GitDiffDto>(`/projects/${id}/git/diff`, { params: { path, staged } }).then(r => r.data),
  incoming: (id: string) =>
    api.get<GitFileChange[]>(`/projects/${id}/git/incoming`).then(r => r.data),
  incomingDiff: (id: string, path: string) =>
    api.get<GitDiffDto>(`/projects/${id}/git/incoming-diff`, { params: { path } }).then(r => r.data),
}

export const settingsApi = {
  get: () => api.get<AppSettings>('/settings').then(r => r.data),
  save: (s: AppSettings) => api.put<AppSettings>('/settings', s).then(r => r.data),
}

export const logsApi = {
  streamUrl: (kind: LogKind, id: string) => `/api${logsBasePath(kind, id)}/stream`,
  history: (kind: LogKind, id: string) =>
    api.get<LogFileEntry[]>(`${logsBasePath(kind, id)}/history`).then(r => r.data),
  historyContent: (kind: LogKind, id: string, filename: string) =>
    api.get<string>(`${logsBasePath(kind, id)}/history/${encodeURIComponent(filename)}`,
      { responseType: 'text', transformResponse: x => x }).then(r => r.data),
  historyDownloadUrl: (kind: LogKind, id: string, filename: string) =>
    `/api${logsBasePath(kind, id)}/history/${encodeURIComponent(filename)}?download=true`,
}

export type LogKind = 'launch' | 'command'

const logsBasePath = (kind: LogKind, id: string) =>
  kind === 'command' ? `/commands/${id}/logs` : `/launches/${id}/logs`

export const logSourceApi = logsApi

export function extractError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { error?: string } | undefined
    return data?.error || err.message
  }
  return err instanceof Error ? err.message : String(err)
}
