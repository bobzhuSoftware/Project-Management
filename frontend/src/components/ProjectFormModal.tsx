import { useState } from 'react'
import { extractError, projectsApi, type LaunchPayload, type ProjectCommandPayload } from '../api'
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

interface LaunchForm {
  uid: string
  id?: string
  name: string
  startCommand: string
  stopCommand: string
  ports: string
}

interface CommandForm {
  uid: string
  id?: string
  name: string
  command: string
  requireStopped: boolean
  script: boolean
  timeoutSeconds: string
}

const newUid = () =>
  (typeof crypto !== 'undefined' && crypto.randomUUID)
    ? crypto.randomUUID()
    : `l-${Math.random().toString(36).slice(2)}`

function parsePorts(ports: string): number[] {
  // Accept any combination of , ; whitespace and the Chinese full-width ， ； 、
  return Array.from(new Set(
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
}

function initLaunches(project: ProjectDto | null): LaunchForm[] {
  if (project && project.launches && project.launches.length > 0) {
    return project.launches.map(l => ({
      uid: newUid(),
      id: l.id,
      name: l.name,
      startCommand: l.startCommand,
      stopCommand: l.stopCommand ?? '',
      ports: (l.ports ?? []).join(', '),
    }))
  }
  return [{ uid: newUid(), name: 'default', startCommand: 'start-dev.cmd', stopCommand: '', ports: '' }]
}

function initCommands(project: ProjectDto | null): CommandForm[] {
  if (project && project.commands && project.commands.length > 0) {
    return project.commands.map(c => ({
      uid: newUid(),
      id: c.id,
      name: c.name,
      command: c.command,
      requireStopped: c.requireStopped,
      script: c.script,
      timeoutSeconds: c.timeoutSeconds != null ? String(c.timeoutSeconds) : '',
    }))
  }
  return []
}

export function ProjectFormModal({ project, defaultCategory, onClose }: Props) {
  const [name, setName] = useState(project?.name ?? '')
  const [rootDirectory, setRootDirectory] = useState(project?.rootDirectory ?? '')
  const [description, setDescription] = useState(project?.description ?? '')
  const [category, setCategory] = useState<ProjectCategory>(
    project?.category ?? defaultCategory ?? 'APPLICATION'
  )
  const [pushEnabled, setPushEnabled] = useState(project?.pushEnabled ?? true)
  const [launches, setLaunches] = useState<LaunchForm[]>(() => initLaunches(project))
  // New (unsaved) launches start expanded for editing; existing ones start collapsed.
  const [expanded, setExpanded] = useState<Set<string>>(
    () => new Set(launches.filter(l => !l.id).map(l => l.uid))
  )
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const updateLaunch = (idx: number, patch: Partial<LaunchForm>) =>
    setLaunches(ls => ls.map((l, i) => (i === idx ? { ...l, ...patch } : l)))
  const addLaunch = () => {
    const uid = newUid()
    setLaunches(ls => [...ls, { uid, name: '', startCommand: '', stopCommand: '', ports: '' }])
    setExpanded(prev => new Set(prev).add(uid))
  }
  const removeLaunch = (idx: number) =>
    setLaunches(ls => ls.filter((_, i) => i !== idx))
  const toggleExpand = (uid: string) =>
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(uid)) next.delete(uid); else next.add(uid)
      return next
    })

  const [commands, setCommands] = useState<CommandForm[]>(() => initCommands(project))
  // New (unsaved) commands start expanded for editing; existing ones start collapsed.
  const [cmdExpanded, setCmdExpanded] = useState<Set<string>>(
    () => new Set(commands.filter(c => !c.id).map(c => c.uid))
  )
  const updateCommand = (idx: number, patch: Partial<CommandForm>) =>
    setCommands(cs => cs.map((c, i) => (i === idx ? { ...c, ...patch } : c)))
  const addCommand = () => {
    const uid = newUid()
    setCommands(cs => [...cs, { uid, name: '', command: '', requireStopped: false, script: false, timeoutSeconds: '' }])
    setCmdExpanded(prev => new Set(prev).add(uid))
  }
  const removeCommand = (idx: number) =>
    setCommands(cs => cs.filter((_, i) => i !== idx))
  const toggleCmdExpand = (uid: string) =>
    setCmdExpanded(prev => {
      const next = new Set(prev)
      if (next.has(uid)) next.delete(uid); else next.add(uid)
      return next
    })

  const save = async () => {
    setError(null); setBusy(true)
    try {
      if (launches.length === 0) throw new Error('At least one launch is required.')
      const payloadLaunches: LaunchPayload[] = launches.map((l, i) => {
        const fail = (msg: string) => {
          setExpanded(prev => new Set(prev).add(l.uid))
          throw new Error(msg)
        }
        if (!l.name.trim()) fail(`Launch #${i + 1} is missing a name.`)
        if (!l.startCommand.trim()) fail(`Launch "${l.name || i + 1}" is missing a start command.`)
        return {
          id: l.id,
          name: l.name.trim(),
          startCommand: l.startCommand.trim(),
          stopCommand: l.stopCommand.trim() || undefined,
          ports: parsePorts(l.ports),
        }
      })
      const payloadCommands: ProjectCommandPayload[] = commands.map((c, i) => {
        const fail = (msg: string) => {
          setCmdExpanded(prev => new Set(prev).add(c.uid))
          throw new Error(msg)
        }
        if (!c.name.trim()) fail(`Command #${i + 1} is missing a name.`)
        if (!c.command.trim()) fail(`Command "${c.name || i + 1}" is missing a command.`)
        let timeoutSeconds: number | undefined
        if (c.timeoutSeconds.trim()) {
          const n = Number(c.timeoutSeconds.trim())
          if (!Number.isInteger(n) || n <= 0) fail(`Command "${c.name || i + 1}" has an invalid timeout (seconds).`)
          timeoutSeconds = n
        }
        return {
          id: c.id,
          name: c.name.trim(),
          command: c.command.trim(),
          requireStopped: c.requireStopped,
          script: c.script,
          timeoutSeconds,
        }
      })
      const payload = {
        name: name.trim(),
        rootDirectory: rootDirectory.trim(),
        description: description.trim() || undefined,
        category,
        pushEnabled,
        launches: payloadLaunches,
        commands: payloadCommands,
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

          <div className="launches-editor">
            <div className="launches-editor-head">
              <div className="launches-editor-title">
                <span className="launches-editor-label">Launches</span>
                <span className="launch-count">{launches.length}</span>
              </div>
              <button type="button" className="primary" onClick={addLaunch}>+ Add Launch</button>
            </div>
            <div className="launches-editor-hint muted">
              A project can have multiple start scripts, e.g. Web / Tray / Client or Dev / Test / Prod.
            </div>
            {launches.map((l, i) => {
              const open = expanded.has(l.uid)
              return (
                <div className={`launch-editor-card${open ? ' open' : ''}`} key={l.uid}>
                  <div className="launch-card-head" onClick={() => toggleExpand(l.uid)}>
                    <span className="launch-index">{i + 1}</span>
                    {open ? (
                      <input
                        className="launch-name-input"
                        value={l.name}
                        onChange={e => updateLaunch(i, { name: e.target.value })}
                        onClick={e => e.stopPropagation()}
                        placeholder="Launch name (e.g. Web / Dev environment)"
                      />
                    ) : (
                      <div className="launch-card-summary">
                        <span className="launch-summary-name">{l.name || 'Untitled launch'}</span>
                        <span className="launch-summary-cmd">{l.startCommand || 'no start command'}</span>
                      </div>
                    )}
                    <button
                      type="button"
                      className="launch-toggle-btn"
                      title={open ? 'Collapse' : 'Edit'}
                      onClick={e => { e.stopPropagation(); toggleExpand(l.uid) }}
                    >
                      {open ? '▾' : '▸'}
                    </button>
                    <button
                      type="button"
                      className="launch-remove-btn"
                      disabled={launches.length <= 1}
                      title={launches.length <= 1 ? 'At least one launch must remain' : 'Remove this launch'}
                      onClick={e => { e.stopPropagation(); removeLaunch(i) }}
                    >
                      🗑
                    </button>
                  </div>
                  {open && (
                    <div className="launch-card-body">
                      <label className="launch-field-label">Start command</label>
                      <input
                        className="mono"
                        value={l.startCommand}
                        onChange={e => updateLaunch(i, { startCommand: e.target.value })}
                        placeholder="e.g. start-dev.cmd"
                      />
                      <label className="launch-field-label">Stop command (optional)</label>
                      <input
                        className="mono"
                        value={l.stopCommand}
                        onChange={e => updateLaunch(i, { stopCommand: e.target.value })}
                        placeholder="e.g. powershell -ExecutionPolicy Bypass -File stop-dev.ps1"
                      />
                      <label className="launch-field-label">Ports (optional)</label>
                      <input
                        className="mono launch-ports-input"
                        value={l.ports}
                        onChange={e => updateLaunch(i, { ports: e.target.value })}
                        placeholder="e.g. 5173, 8085, 3306"
                      />
                    </div>
                  )}
                </div>
              )
            })}
          </div>

          <div className="launches-editor">
            <div className="launches-editor-head">
              <div className="launches-editor-title">
                <span className="launches-editor-label">Commands</span>
                <span className="launch-count">{commands.length}</span>
              </div>
              <button type="button" className="primary" onClick={addCommand}>+ Add Command</button>
            </div>
            <div className="launches-editor-hint muted">
              Custom maintenance commands, e.g. Clean / Build Frontend / Build Backend. They appear in the project's ⋯ menu.
            </div>
            {commands.map((c, i) => {
              const open = cmdExpanded.has(c.uid)
              return (
                <div className={`launch-editor-card${open ? ' open' : ''}`} key={c.uid}>
                  <div className="launch-card-head" onClick={() => toggleCmdExpand(c.uid)}>
                    <span className="launch-index">{i + 1}</span>
                    {open ? (
                      <input
                        className="launch-name-input"
                        value={c.name}
                        onChange={e => updateCommand(i, { name: e.target.value })}
                        onClick={e => e.stopPropagation()}
                        placeholder="Command name (e.g. Clean / Build Frontend)"
                      />
                    ) : (
                      <div className="launch-card-summary">
                        <span className="launch-summary-name">{c.name || 'Untitled command'}</span>
                        <span className="launch-summary-cmd">{c.command || 'no command'}</span>
                      </div>
                    )}
                    <button
                      type="button"
                      className="launch-toggle-btn"
                      title={open ? 'Collapse' : 'Edit'}
                      onClick={e => { e.stopPropagation(); toggleCmdExpand(c.uid) }}
                    >
                      {open ? '▾' : '▸'}
                    </button>
                    <button
                      type="button"
                      className="launch-remove-btn"
                      title="Remove this command"
                      onClick={e => { e.stopPropagation(); removeCommand(i) }}
                    >
                      🗑
                    </button>
                  </div>
                  {open && (
                    <div className="launch-card-body">
                      <label className="launch-field-label">Command</label>
                      <textarea
                        className="mono"
                        value={c.command}
                        onChange={e => updateCommand(i, { command: e.target.value })}
                        placeholder="e.g. npm --prefix frontend run build"
                      />
                      <label className="checkbox-inline" style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 'normal', marginTop: 8 }}>
                        <input
                          type="checkbox"
                          checked={c.requireStopped}
                          onChange={e => updateCommand(i, { requireStopped: e.target.checked })}
                          style={{ width: 'auto' }}
                        />
                        Require all launches stopped before running (e.g. clean)
                      </label>
                      <label className="checkbox-inline" style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 'normal', marginTop: 8 }}>
                        <input
                          type="checkbox"
                          checked={c.script}
                          onChange={e => updateCommand(i, { script: e.target.checked })}
                          style={{ width: 'auto' }}
                        />
                        Script (run in background, stream output to logs — no timeout; use for long builds like build-prod.cmd)
                      </label>
                      <label className="launch-field-label">Timeout seconds (optional, default 120)</label>
                      <input
                        className="mono"
                        value={c.timeoutSeconds}
                        onChange={e => updateCommand(i, { timeoutSeconds: e.target.value })}
                        placeholder="e.g. 300"
                        disabled={c.script}
                        title={c.script ? 'Not used for script commands (they run without a timeout)' : undefined}
                      />
                    </div>
                  )}
                </div>
              )
            })}
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
