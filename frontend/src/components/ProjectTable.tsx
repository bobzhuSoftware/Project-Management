import { Fragment, useEffect, useRef, useState } from 'react'
import type { GitStatusDto, LaunchDto, ProjectDto, ProjectStatus } from '../types'

interface Props {
  projects: ProjectDto[]
  busyId: string | null
  gitStatus: Record<string, GitStatusDto | undefined>
  gitLoading: Record<string, boolean>
  onStart: (l: LaunchDto) => void
  onStop: (l: LaunchDto) => void
  onClean: (p: ProjectDto) => void
  onEdit: (p: ProjectDto) => void
  onDelete: (p: ProjectDto) => void
  onLogs: (l: LaunchDto, projectName: string) => void
  onSync: (p: ProjectDto) => void
  onShowPull: (p: ProjectDto) => void
  onShowChanges: (p: ProjectDto) => void
  onGitRefresh: (p: ProjectDto) => void
  onReorder: (orderedIds: string[]) => void
  onOpenFolder: (p: ProjectDto) => void
}

function uptime(startedAt?: string | null): string {
  if (!startedAt) return '-'
  const ms = Date.now() - new Date(startedAt).getTime()
  if (ms < 0) return '-'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}

interface PortItem { port: number; registered: boolean }

function renderPorts(p: LaunchDto, running: boolean, external: boolean): JSX.Element | string {
  const registered = new Set(p.ports ?? [])
  const detected = new Set((p.detectedPorts ?? []).filter(x => typeof x === 'number'))
  const items: PortItem[] = []
  for (const port of registered) items.push({ port, registered: true })
  for (const port of detected) if (!registered.has(port)) items.push({ port, registered: false })
  if (items.length === 0) return '-'
  items.sort((a, b) => a.port - b.port)
  const clickable = running || external
  return (
    <span>
      {items.map((it, i) => (
        <span key={it.port}>
          {i > 0 && ', '}
          {clickable
            ? <a className="port-link" href={`http://localhost:${it.port}`} target="_blank" rel="noreferrer">{it.port}</a>
            : it.port}
          {!it.registered && <span className="port-auto" title="Auto-detected listening port (not registered in project config)"> (auto)</span>}
        </span>
      ))}
    </span>
  )
}

function renderGitBadge(s: GitStatusDto): { cls: string; text: string; title: string } {
  if (s.error) return { cls: 'git-badge err', text: 'error', title: s.error }
  if (!s.repo) return { cls: 'git-badge none', text: 'non-git', title: 'Root directory is not a git repository' }
  const dirty = s.staged + s.modified + s.untracked + s.conflicting
  if (s.conflicting > 0) return { cls: 'git-badge err', text: `! ${s.conflicting} conflict`, title: 'Merge conflicts present' }
  if (dirty > 0) {
    // Local changes take visual priority, but still surface a behind count so the
    // user knows a pull is needed before syncing.
    const base = `● ${dirty} change${dirty > 1 ? 's' : ''}`
    const text = s.behind > 0 ? `${base} / ↓ ${s.behind}` : base
    const title = `staged ${s.staged}, modified ${s.modified}, untracked ${s.untracked}`
      + (s.behind > 0 ? ` — remote is ${s.behind} commit(s) ahead, pull required` : '')
    return { cls: s.behind > 0 ? 'git-badge warn' : 'git-badge dirty', text, title }
  }
  if (!s.hasUpstream) return { cls: 'git-badge warn', text: 'no upstream', title: 'Branch has no upstream remote tracking branch' }
  if (s.behind > 0 && s.ahead > 0) return { cls: 'git-badge warn', text: `↕ ${s.ahead}/${s.behind}`, title: `${s.ahead} ahead, ${s.behind} behind — pull then push` }
  if (s.behind > 0) return { cls: 'git-badge warn', text: `↓ ${s.behind} behind`, title: `Remote has ${s.behind} new commit(s) — pull required` }
  if (s.ahead > 0) return { cls: 'git-badge ahead', text: `↑ ${s.ahead} to push`, title: `${s.ahead} local commit(s) not yet pushed` }
  if (s.remoteError) return { cls: 'git-badge warn', text: '⚠ unverified', title: s.remoteError }
  return { cls: 'git-badge ok', text: '✓ synced', title: 'In sync with remote (verified)' }
}

interface GitHandlers {
  onSync: (p: ProjectDto) => void
  onShowPull: (p: ProjectDto) => void
  onShowChanges: (p: ProjectDto) => void
  onGitRefresh: (p: ProjectDto) => void
}

function renderGit(
  p: ProjectDto,
  status: GitStatusDto | undefined,
  loading: boolean,
  busy: boolean,
  handlers: GitHandlers,
): JSX.Element {
  const { onSync, onShowPull, onShowChanges, onGitRefresh } = handlers
  if (!status) {
    return <span className="muted">{loading ? '…' : '—'}</span>
  }
  const { cls, text, title } = renderGitBadge(status)
  const canSync = status.repo && !status.error && status.behind === 0 && status.conflicting === 0 && status.hasUpstream
  const needsSync = status.repo && !status.error && !status.inSync
  const hasChanges = status.repo && !status.error &&
    (status.staged + status.modified + status.untracked + status.conflicting) > 0
  // No local changes but behind remote: make the badge clickable so the user can
  // open the pull view and fast-forward from there.
  const behindOnly = !hasChanges && status.repo && !status.error && status.behind > 0
  return (
    <span className="git-cell">
      {status.repo && !p.pushEnabled && (
        <span
          className="git-push-disabled"
          title="Push disabled — edit the project to re-enable"
        >
          🔒
        </span>
      )}
      {hasChanges && (
        <button
          type="button"
          className={`${cls} git-badge-btn`}
          title={`${title} — click to view changed files`}
          onClick={(e) => { e.stopPropagation(); onShowChanges(p) }}
        >
          {text}
        </button>
      )}
      {!hasChanges && behindOnly && (
        <button
          type="button"
          className={`${cls} git-badge-btn`}
          title={`${title} — click to pull`}
          onClick={(e) => { e.stopPropagation(); onShowPull(p) }}
        >
          {text}
        </button>
      )}
      {!hasChanges && !behindOnly && (
        <span className={cls} title={title}>{text}</span>
      )}
      {status.repo && needsSync && (
        <button
          className="git-sync-btn"
          disabled={busy || loading}
          title={canSync ? 'Commit local changes and push to remote' : 'Open sync — pull behind commits / resolve conflicts first'}
          onClick={() => onSync(p)}
        >
          Sync
        </button>
      )}
      <button
        className="git-refresh-btn"
        disabled={loading || busy}
        title="Refresh git status"
        onClick={() => onGitRefresh(p)}
      >
        ↻
      </button>
    </span>
  )
}

function aggregateStatus(p: ProjectDto): ProjectStatus {
  const s = (p.launches ?? []).map(l => l.status)
  if (s.includes('RUNNING')) return 'RUNNING'
  if (s.includes('ATTACHED')) return 'ATTACHED'
  if (s.includes('EXTERNAL')) return 'EXTERNAL'
  if (s.includes('ERROR')) return 'ERROR'
  return 'STOPPED'
}

export function ProjectTable({ projects, busyId, gitStatus, gitLoading, onStart, onStop, onClean, onEdit, onDelete, onLogs, onSync, onShowPull, onShowChanges, onGitRefresh, onReorder, onOpenFolder }: Props) {
  const dragItem = useRef<number | null>(null)
  const dragOverItem = useRef<number | null>(null)
  const [dragIdx, setDragIdx] = useState<number | null>(null)
  const [menuFor, setMenuFor] = useState<{ id: string; x: number; y: number } | null>(null)
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())

  const toggle = (id: string) => setCollapsed(prev => {
    const next = new Set(prev)
    if (next.has(id)) next.delete(id); else next.add(id)
    return next
  })

  const openMenu = (e: React.MouseEvent, id: string) => {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
    setMenuFor(prev => (prev?.id === id ? null : { id, x: r.right, y: r.bottom }))
  }
  const closeMenu = () => setMenuFor(null)

  useEffect(() => {
    if (!menuFor) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closeMenu() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [menuFor])

  const handleDragStart = (idx: number) => {
    dragItem.current = idx
    setDragIdx(idx)
  }

  const handleDragEnter = (idx: number) => {
    dragOverItem.current = idx
  }

  const handleDragEnd = () => {
    if (dragItem.current !== null && dragOverItem.current !== null && dragItem.current !== dragOverItem.current) {
      const reordered = [...projects]
      const [removed] = reordered.splice(dragItem.current, 1)
      reordered.splice(dragOverItem.current, 0, removed)
      onReorder(reordered.map(p => p.id))
    }
    dragItem.current = null
    dragOverItem.current = null
    setDragIdx(null)
  }

  return (
    <>
    <table>
      <thead>
        <tr>
          <th></th>
          <th>Name</th>
          <th>Status</th>
          <th>Ports</th>
          <th>PID</th>
          <th>Uptime</th>
          <th>Git</th>
          <th>Actions</th>
          <th className="row-spacer"></th>
        </tr>
      </thead>
      <tbody>
        {projects.map((p, idx) => {
          const launches = p.launches ?? []
          const agg = aggregateStatus(p)
          const projBusy = busyId === p.id
          const isOpen = !collapsed.has(p.id)
          // Single-launch projects render as one compact row (no tree, no child).
          const single = launches.length === 1
          const only = single ? launches[0] : undefined
          const onlyRunning = !!only && (only.status === 'RUNNING' || only.status === 'ATTACHED')
          const onlyExternal = !!only && only.status === 'EXTERNAL'
          const onlyStoppable = onlyRunning || onlyExternal
          const onlyBusy = !!only && busyId === only.id
          return (
            <Fragment key={p.id}>
            <tr
              draggable
              onDragStart={() => handleDragStart(idx)}
              onDragEnter={() => handleDragEnter(idx)}
              onDragEnd={handleDragEnd}
              onDragOver={(e) => e.preventDefault()}
              className={`project-row${dragIdx === idx ? ' dragging' : ''}`}
            >
              <td className="expand-cell">
                <div className="expand-cell-inner">
                  {!single && (
                    <button
                      className="expand-btn"
                      title={isOpen ? 'Collapse launches' : 'Expand launches'}
                      onClick={() => toggle(p.id)}
                    >
                      {isOpen ? '▾' : '▸'}
                    </button>
                  )}
                  <span className="drag-handle" title="Drag to reorder">⠿</span>
                </div>
              </td>
              <td className="name-cell">
                <div className="name-row">
                  <span className="name-text">{p.name}</span>
                  <button
                    className="open-folder-btn"
                    title={`Open folder: ${p.rootDirectory}`}
                    onClick={() => onOpenFolder(p)}
                  >
                    📂
                  </button>
                  {!single && <span className="launch-count" title="Number of launches">{launches.length} launches</span>}
                </div>
                {p.description && <div className="muted name-desc">{p.description}</div>}
              </td>
              {single && only ? (
                <>
                  <td><span className={`badge ${only.status}`}>{only.status}</span></td>
                  <td>{renderPorts(only, onlyRunning, onlyExternal)}</td>
                  <td>{only.pid ?? '-'}</td>
                  <td>{uptime(only.startedAt)}</td>
                </>
              ) : (
                <>
                  <td><span className={`badge ${agg}`}>{agg}</span></td>
                  <td className="muted">—</td>
                  <td className="muted">—</td>
                  <td className="muted">—</td>
                </>
              )}
              <td>{renderGit(p, gitStatus[p.id], !!gitLoading[p.id], projBusy, { onSync, onShowPull, onShowChanges, onGitRefresh })}</td>
              <td className="actions">
                {single && only && (
                  <>
                    {!onlyRunning && !onlyExternal && (
                      <button className="success" disabled={onlyBusy} onClick={() => onStart(only)}>Start</button>
                    )}
                    {onlyStoppable && (
                      <button className="danger" disabled={onlyBusy} onClick={() => onStop(only)}>Stop</button>
                    )}
                    <button disabled={onlyBusy} onClick={() => onLogs(only, p.name)}>Logs</button>
                  </>
                )}
                <button disabled={projBusy} onClick={() => onEdit(p)}>Edit</button>
                <button
                  className={`action-menu-btn${menuFor?.id === p.id ? ' open' : ''}`}
                  disabled={projBusy}
                  title="More actions"
                  onClick={(e) => openMenu(e, p.id)}
                >
                  ⋯
                </button>
              </td>
              <td className="row-spacer"></td>
            </tr>
            {!single && isOpen && launches.map(l => {
              const running = l.status === 'RUNNING' || l.status === 'ATTACHED'
              const external = l.status === 'EXTERNAL'
              const stoppable = running || external
              const busy = busyId === l.id
              return (
                <tr key={l.id} className="launch-row">
                  <td className="launch-indent"></td>
                  <td className="launch-name-cell">
                    <span className="launch-branch">↳</span>
                    <span className="launch-name">{l.name}</span>
                  </td>
                  <td><span className={`badge ${l.status}`}>{l.status}</span></td>
                  <td>{renderPorts(l, running, external)}</td>
                  <td>{l.pid ?? '-'}</td>
                  <td>{uptime(l.startedAt)}</td>
                  <td></td>
                  <td className="actions">
                    {!running && !external && (
                      <button className="success" disabled={busy} onClick={() => onStart(l)}>Start</button>
                    )}
                    {stoppable && (
                      <button className="danger" disabled={busy} onClick={() => onStop(l)}>Stop</button>
                    )}
                    <button disabled={busy} onClick={() => onLogs(l, p.name)}>Logs</button>
                  </td>
                  <td className="row-spacer"></td>
                </tr>
              )
            })}
            </Fragment>
          )
        })}
      </tbody>
    </table>
    {menuFor && (() => {
      const p = projects.find(x => x.id === menuFor.id)
      if (!p) return null
      const anyRunning = (p.launches ?? []).some(l => l.status === 'RUNNING' || l.status === 'ATTACHED')
      const run = (fn: () => void) => { closeMenu(); fn() }
      return (
        <>
          <div className="action-menu-backdrop" onClick={closeMenu} />
          <div className="action-menu" style={{ top: menuFor.y + 4, left: menuFor.x - 150 }}>
            {p.cleanCommand && (
              <button
                disabled={anyRunning}
                title={anyRunning ? 'Stop all launches before cleaning' : 'Run the clean command to remove build artifacts'}
                onClick={() => run(() => onClean(p))}
              >
                Clean
              </button>
            )}
            <button
              className="danger-text"
              disabled={anyRunning}
              onClick={() => run(() => onDelete(p))}
            >
              Delete
            </button>
          </div>
        </>
      )
    })()}
    </>
  )
}
