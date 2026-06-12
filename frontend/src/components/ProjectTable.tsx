import type { ProjectDto } from '../types'

interface Props {
  projects: ProjectDto[]
  busyId: string | null
  onStart: (p: ProjectDto) => void
  onStop: (p: ProjectDto) => void
  onEdit: (p: ProjectDto) => void
  onDelete: (p: ProjectDto) => void
  onLogs: (p: ProjectDto) => void
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

export function ProjectTable({ projects, busyId, onStart, onStop, onEdit, onDelete, onLogs }: Props) {
  return (
    <table>
      <thead>
        <tr>
          <th>Name</th>
          <th>Status</th>
          <th>Ports</th>
          <th>PID</th>
          <th>Uptime</th>
          <th>Root</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        {projects.map(p => {
          const running = p.status === 'RUNNING' || p.status === 'ATTACHED'
          const external = p.status === 'EXTERNAL'
          const stoppable = running || external
          const busy = busyId === p.id
          const openPort = stoppable ? pickOpenPort(p) : null
          return (
            <tr key={p.id}>
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
              <td className="muted" style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p.rootDirectory}</td>
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
