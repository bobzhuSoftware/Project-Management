export type ProjectStatus = 'RUNNING' | 'ATTACHED' | 'EXTERNAL' | 'STOPPED' | 'ERROR'

export interface ProjectDto {
  id: string
  name: string
  rootDirectory: string
  startCommand: string
  stopCommand?: string | null
  ports: number[]
  description?: string | null
  createdAt: string
  updatedAt: string
  status: ProjectStatus
  pid?: number | null
  startedAt?: string | null
  detectedPorts?: number[] | null
}

export interface ProjectFormValues {
  name: string
  rootDirectory: string
  startCommand: string
  stopCommand: string
  ports: string // comma separated in form
  description: string
}
