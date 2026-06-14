import axios from 'axios'
import type { GitStatusDto, GitSyncResultDto, ProjectDto } from './types'

const api = axios.create({ baseURL: '/api' })

export interface ProjectPayload {
  name: string
  rootDirectory: string
  startCommand: string
  stopCommand?: string
  ports: number[]
  description?: string
}

export const projectsApi = {
  list: () => api.get<ProjectDto[]>('/projects').then(r => r.data),
  create: (p: ProjectPayload) => api.post<ProjectDto>('/projects', p).then(r => r.data),
  update: (id: string, p: ProjectPayload) => api.put<ProjectDto>(`/projects/${id}`, p).then(r => r.data),
  remove: (id: string) => api.delete(`/projects/${id}`),
  start: (id: string) => api.post<ProjectDto>(`/projects/${id}/start`).then(r => r.data),
  stop: (id: string) => api.post<ProjectDto>(`/projects/${id}/stop`).then(r => r.data),
  reorder: (orderedIds: string[]) => api.put('/projects/reorder', orderedIds),
}

export interface LogFileEntry {
  filename: string
  size: number
  modifiedAt: string
}

export const gitApi = {
  status: (id: string, refresh = false) =>
    api.get<GitStatusDto>(`/projects/${id}/git/status`, { params: { refresh } }).then(r => r.data),
  sync: (id: string, message: string) =>
    api.post<GitSyncResultDto>(`/projects/${id}/git/sync`, { message }).then(r => r.data),
}

export const logsApi = {
  history: (id: string) =>
    api.get<LogFileEntry[]>(`/projects/${id}/logs/history`).then(r => r.data),
  historyContent: (id: string, filename: string) =>
    api.get<string>(`/projects/${id}/logs/history/${encodeURIComponent(filename)}`,
      { responseType: 'text', transformResponse: x => x }).then(r => r.data),
  historyDownloadUrl: (id: string, filename: string) =>
    `/api/projects/${id}/logs/history/${encodeURIComponent(filename)}?download=true`,
}

export function extractError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { error?: string } | undefined
    return data?.error || err.message
  }
  return err instanceof Error ? err.message : String(err)
}
