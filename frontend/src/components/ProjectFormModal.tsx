import { useState } from 'react'
import { extractError, projectsApi } from '../api'
import type { ProjectCategory, ProjectDto } from '../types'

interface Props {
  project: ProjectDto | null
  defaultCategory?: ProjectCategory
  onClose: (changed: boolean) => void
}

const CATEGORY_OPTIONS: { value: ProjectCategory; label: string }[] = [
  { value: 'APPLICATION', label: 'Application' },
  { value: 'DATABASE', label: 'Database' },
  { value: 'SCRIPT', label: 'Script' },
  { value: 'OTHER', label: 'Other' },
]

export function ProjectFormModal({ project, defaultCategory, onClose }: Props) {
  const [name, setName] = useState(project?.name ?? '')
  const [rootDirectory, setRootDirectory] = useState(project?.rootDirectory ?? '')
  const [startCommand, setStartCommand] = useState(project?.startCommand ?? 'start-dev.cmd')
  const [stopCommand, setStopCommand] = useState(project?.stopCommand ?? '')
  const [cleanCommand, setCleanCommand] = useState(project?.cleanCommand ?? '')
  const [ports, setPorts] = useState((project?.ports ?? []).join(', '))
  const [description, setDescription] = useState(project?.description ?? '')
  const [category, setCategory] = useState<ProjectCategory>(
    project?.category ?? defaultCategory ?? 'APPLICATION'
  )
  const [pushEnabled, setPushEnabled] = useState(project?.pushEnabled ?? true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const save = async () => {
    setError(null); setBusy(true)
    try {
      // Accept any combination of , ; whitespace and the Chinese full-width ， ； 、
      const parsedPorts = Array.from(new Set(
        ports
          .split(/[\s,;，；、]+/)
          .map(s => s.trim())
          .filter(Boolean)
          .map(s => {
            const n = Number(s)
            if (!Number.isInteger(n) || n <= 0 || n > 65535) throw new Error(`Invalid port: ${s}`)
            return n
          })
      ))
      const payload = {
        name: name.trim(),
        rootDirectory: rootDirectory.trim(),
        startCommand: startCommand.trim(),
        stopCommand: stopCommand.trim() || undefined,
        cleanCommand: cleanCommand.trim() || undefined,
        ports: parsedPorts,
        description: description.trim() || undefined,
        category,
        pushEnabled,
      }
      if (project) await projectsApi.update(project.id, payload)
      else await projectsApi.create(payload)
      onClose(true)
    } catch (e) {
      setError(extractError(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop">
      <div className="modal" onClick={e => e.stopPropagation()}>
        <h2>{project ? 'Edit Project' : 'New Project'}</h2>
        {error && <div className="error-banner">{error}</div>}
        <div className="modal-body">
          <div className="form-row">
            <label>Category</label>
            <select value={category} onChange={e => setCategory(e.target.value as ProjectCategory)}>
              {CATEGORY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
          <div className="form-row">
            <label>Name</label>
            <input value={name} onChange={e => setName(e.target.value)} placeholder="A Stock Stock Card" />
          </div>
          <div className="form-row">
            <label>Root Directory (absolute Windows path)</label>
            <input value={rootDirectory} onChange={e => setRootDirectory(e.target.value)}
                   placeholder="C:\Users\BOBZHU01\Projects\A Stock Stock Card" />
          </div>
          <div className="form-row">
            <label>Start Command (relative to root, run via cmd /c)</label>
            <textarea value={startCommand} onChange={e => setStartCommand(e.target.value)}
                      placeholder="start-dev.cmd" />
          </div>
          <div className="form-row">
            <label>Stop Command (optional)</label>
            <textarea value={stopCommand} onChange={e => setStopCommand(e.target.value)}
                      placeholder="powershell -ExecutionPolicy Bypass -File stop-dev.ps1" />
          </div>
          <div className="form-row">
            <label>Clean Command (optional, run only when stopped)</label>
            <textarea value={cleanCommand} onChange={e => setCleanCommand(e.target.value)}
                      placeholder="mvn clean" />
          </div>
          <div className="form-row">
            <label>Ports (multiple supported — e.g. web + API + DB. Used for status detection and kill fallback)</label>
            <input value={ports} onChange={e => setPorts(e.target.value)} placeholder="5173, 8085, 3306" />
          </div>
          <div className="form-row">
            <label>Description (optional)</label>
            <input value={description} onChange={e => setDescription(e.target.value)} />
          </div>
          <div className="form-row">
            <label>Git Push</label>
            <label className="checkbox-inline" style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 'normal' }}>
              <input
                type="checkbox"
                checked={pushEnabled}
                onChange={e => setPushEnabled(e.target.checked)}
                style={{ width: 'auto' }}
              />
              Allow pushing to remote (disabling blocks push from this app and local git via a pre-push hook)
            </label>
          </div>
        </div>
        <div className="form-actions">
          <button onClick={() => onClose(false)} disabled={busy}>Cancel</button>
          <button className="primary" onClick={save} disabled={busy}>
            {busy ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}
