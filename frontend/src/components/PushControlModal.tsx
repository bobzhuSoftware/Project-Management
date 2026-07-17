import { useState } from 'react'
import { extractError, projectsApi } from '../api'
import type { ProjectDto } from '../types'

interface Props {
  projects: ProjectDto[]
  onClose: (changed: boolean) => void
}

export function PushControlModal({ projects, onClose }: Props) {
  // Local draft of each project's push flag; only changed ones are persisted on save.
  const [state, setState] = useState<Record<string, boolean>>(
    () => Object.fromEntries(projects.map(p => [p.id, p.pushEnabled])),
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const toggle = (id: string) => setState(s => ({ ...s, [id]: !s[id] }))
  const setAll = (value: boolean) =>
    setState(Object.fromEntries(projects.map(p => [p.id, value])))

  const save = async () => {
    setBusy(true); setError(null)
    try {
      const changed = projects.filter(p => state[p.id] !== p.pushEnabled)
      for (const p of changed) {
        await projectsApi.setPushEnabled(p.id, state[p.id])
      }
      onClose(changed.length > 0)
    } catch (e) {
      setError(extractError(e))
    } finally {
      setBusy(false)
    }
  }

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') onClose(false)
  }

  return (
    <div className="modal-backdrop" onClick={() => onClose(false)}>
      <div className="modal" onClick={e => e.stopPropagation()} onKeyDown={handleKey}>
        <h2>🔐 Git Push Control</h2>
        {error && <div className="error-banner">{error}</div>}
        <div className="modal-body">
          <p className="muted" style={{ marginTop: 0, lineHeight: 1.6 }}>
            Check a project to <strong>allow</strong> pushing to its remote. Unchecking installs a
            pre-push hook that blocks push from this app <em>and</em> from local git.
          </p>
          <div style={{ display: 'flex', gap: 8, margin: '4px 0 12px' }}>
            <button onClick={() => setAll(true)} disabled={busy}>Enable all</button>
            <button onClick={() => setAll(false)} disabled={busy}>Disable all</button>
          </div>
          {projects.length === 0 ? (
            <div className="muted">No projects.</div>
          ) : (
            <table className="push-control-table">
              <thead>
                <tr>
                  <th style={{ textAlign: 'left' }}>Project</th>
                  <th style={{ textAlign: 'center', width: 80 }}>Push</th>
                  <th style={{ textAlign: 'left', width: 130 }}>Status</th>
                </tr>
              </thead>
              <tbody>
                {projects.map(p => (
                  <tr key={p.id} onClick={() => !busy && toggle(p.id)} style={{ cursor: busy ? 'default' : 'pointer' }}>
                    <td>{p.name}</td>
                    <td style={{ textAlign: 'center' }}>
                      <input
                        type="checkbox"
                        checked={!!state[p.id]}
                        onChange={() => toggle(p.id)}
                        onClick={e => e.stopPropagation()}
                        disabled={busy}
                        style={{ width: 'auto' }}
                      />
                    </td>
                    <td className="muted">{state[p.id] ? '🔓 enabled' : '🔒 disabled'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="form-actions">
          <button onClick={() => onClose(false)} disabled={busy}>Cancel</button>
          <button className="primary" onClick={save} disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}
