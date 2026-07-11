import { useRef, useState } from 'react'
import type { GitStatusDto, ProjectDto } from '../types'

interface Props {
  projects: ProjectDto[]
  busyId: string | null
  gitStatus: Record<string, GitStatusDto | undefined>
  gitLoading: Record<string, boolean>
  onStart: (p: ProjectDto) => void
  onStop: (p: ProjectDto) => void
  onEdit: (p: ProjectDto) => void
  onDelete: (p: ProjectDto) => void
  onLogs: (p: ProjectDto) => void
  onSync: (p: ProjectDto) => void
  onShowChanges: (p: ProjectDto) => void
  onGitRefresh: (p: ProjectDto) => void
  onReorder: (orderedIds: string[]) => void
  onOpenFolder: (p: ProjectDto) => void
}

function pickOpenPort(p: ProjectDto): number | null {
  const registered = (p.ports ?? []).filter(x => typeof x === 'number')
  if (registered.length > 0) return registered[0]
  const detected = (p.detectedPorts ?? []).filter(x => typeof x === 'number')
  if (detected.length > 0) return detected[0]
  return null
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

function renderPorts(p: ProjectDto, running: boolean, external: boolean): JSX.Element | string {
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
          {!it.registered && <span className="port-auto" title="自动探测到的监听端口（未在项目配置里登记）"> (auto)</span>}
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
  if (dirty > 0) return { cls: 'git-badge dirty', text: `● ${dirty} change${dirty > 1 ? 's' : ''}`, title: `staged ${s.staged}, modified ${s.modified}, untracked ${s.untracked}` }
  if (!s.hasUpstream) return { cls: 'git-badge warn', text: 'no upstream', title: 'Branch has no upstream remote tracking branch' }
  if (s.behind > 0 && s.ahead > 0) return { cls: 'git-badge warn', text: `↕ ${s.ahead}/${s.behind}`, title: `${s.ahead} ahead, ${s.behind} behind` }
  if (s.behind > 0) return { cls: 'git-badge warn', text: `↓ ${s.behind} behind`, title: `Remote has ${s.behind} new commit(s)` }
  if (s.ahead > 0) return { cls: 'git-badge ahead', text: `↑ ${s.ahead} to push`, title: `${s.ahead} local commit(s) not yet pushed` }
  return { cls: 'git-badge ok', text: '✓ synced', title: 'In sync with remote' }
}

function renderGit(
  p: ProjectDto,
  status: GitStatusDto | undefined,
  loading: boolean,
  busy: boolean,
  onSync: (p: ProjectDto) => void,
  onShowChanges: (p: ProjectDto) => void,
  onGitRefresh: (p: ProjectDto) => void,
): JSX.Element {
  if (!status) {
    return <span className="muted">{loading ? '…' : '—'}</span>
  }
  const { cls, text, title } = renderGitBadge(status)
  const canSync = status.repo && !status.error && status.behind === 0 && status.conflicting === 0 && status.hasUpstream
  const needsSync = status.repo && !status.error && !status.inSync
  const hasChanges = status.repo && !status.error &&
    (status.staged + status.modified + status.untracked + status.conflicting) > 0
  return (
    <span className="git-cell">
      {hasChanges ? (
        <button
          type="button"
          className={`${cls} git-badge-btn`}
          title={`${title} — click to view changed files`}
          onClick={(e) => { e.stopPropagation(); onShowChanges(p) }}
        >
          {text}
        </button>
      ) : (
        <span className={cls} title={title}>{text}</span>
      )}
      {status.repo && needsSync && (
        <button
          className="git-sync-btn"
          disabled={busy || loading || !canSync}
          title={canSync ? 'Commit local changes and push to remote' : 'Resolve conflicts / pull behind commits first'}
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

export function ProjectTable({ projects, busyId, gitStatus, gitLoading, onStart, onStop, onEdit, onDelete, onLogs, onSync, onShowChanges, onGitRefresh, onReorder, onOpenFolder }: Props) {
  const dragItem = useRef<number | null>(null)
  const dragOverItem = useRef<number | null>(null)
  const [dragIdx, setDragIdx] = useState<number | null>(null)

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
          <th>Root</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        {projects.map((p, idx) => {
          const running = p.status === 'RUNNING' || p.status === 'ATTACHED'
          const external = p.status === 'EXTERNAL'
          const stoppable = running || external
          const busy = busyId === p.id
          const openPort = stoppable ? pickOpenPort(p) : null
          return (
            <tr
              key={p.id}
              draggable
              onDragStart={() => handleDragStart(idx)}
              onDragEnter={() => handleDragEnter(idx)}
              onDragEnd={handleDragEnd}
              onDragOver={(e) => e.preventDefault()}
              className={dragIdx === idx ? 'dragging' : undefined}
            >
              <td className="drag-handle" title="拖动排序">⠿</td>
              <td>
                <div>{p.name}</div>
                {p.description && <div className="muted">{p.description}</div>}
              </td>
              <td><span className={`badge ${p.status}`}>{p.status}</span></td>
              <td>
                {renderPorts(p, running, external)}
              </td>
              <td>{p.pid ?? '-'}</td>
              <td>{uptime(p.startedAt)}</td>
              <td>{renderGit(p, gitStatus[p.id], !!gitLoading[p.id], busy, onSync, onShowChanges, onGitRefresh)}</td>
              <td className="root-cell muted">
                <button
                  className="open-folder-btn"
                  title="在文件资源管理器中打开此目录"
                  onClick={() => onOpenFolder(p)}
                >
                  📂
                </button>
                <span className="root-path" title={p.rootDirectory}>{p.rootDirectory}</span>
              </td>
              <td className="actions">
                {!running && !external && (
                  <button className="success" disabled={busy} onClick={() => onStart(p)}>Start</button>
                )}
                {stoppable && (
                  <button className="danger" disabled={busy} onClick={() => onStop(p)}>Stop</button>
                )}
                {openPort != null && (
                  <button
                    disabled={busy}
                    title={`在新标签页打开 http://localhost:${openPort}`}
                    onClick={() => window.open(`http://localhost:${openPort}`, '_blank', 'noopener,noreferrer')}
                  >
                    Open
                  </button>
                )}
                <button disabled={busy} onClick={() => onLogs(p)}>Logs</button>
                <button disabled={busy} onClick={() => onEdit(p)}>Edit</button>
                <button disabled={busy || running} onClick={() => onDelete(p)}>Delete</button>
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
