import { useMemo, useState } from 'react'
import { extractError, launchesApi } from '../api'
import type { LaunchDto, ProjectDto, Reach } from '../types'

interface Props {
  projects: ProjectDto[]
  onClose: (changed: boolean) => void
}

const REACH_OPTIONS: { value: Reach; label: string; title: string }[] = [
  { value: 'LOCAL', label: 'Local', title: 'localhost only (this machine)' },
  { value: 'WIFI', label: 'Wi-Fi', title: 'Reachable as <alias>.local on the same Wi-Fi' },
  { value: 'INTERNET', label: 'Internet', title: 'Exposed via a temporary public link' },
]

const TTL_OPTIONS: { label: string; minutes: number | null }[] = [
  { label: '1 hour', minutes: 60 },
  { label: '8 hours', minutes: 8 * 60 },
  { label: '24 hours', minutes: 24 * 60 },
  { label: '7 days', minutes: 7 * 24 * 60 },
  { label: 'No expiry', minutes: null },
]

interface Row {
  launch: LaunchDto
  projectName: string
  /** True on the first launch of each project so we can visually group. */
  groupStart: boolean
}

export function ShareControlModal({ projects, onClose }: Props) {
  // Only launches with a stable alias can be shared beyond localhost.
  const rows = useMemo<Row[]>(() => {
    const out: Row[] = []
    for (const p of projects) {
      const shareable = (p.launches ?? []).filter(l => !!l.alias)
      shareable.forEach((launch, i) => {
        out.push({ launch, projectName: p.name, groupStart: i === 0 })
      })
    }
    return out
  }, [projects])

  const [reach, setReach] = useState<Record<string, Reach>>(
    () => Object.fromEntries(rows.map(r => [r.launch.id, r.launch.reach ?? 'LOCAL'])),
  )
  const [ttl, setTtl] = useState<Record<string, number | null>>(
    () => Object.fromEntries(rows.map(r => [r.launch.id, 60])),
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const setAll = (value: Reach) =>
    setReach(Object.fromEntries(rows.map(r => [r.launch.id, value])))

  const save = async () => {
    setBusy(true); setError(null)
    try {
      const changed = rows.filter(r => reach[r.launch.id] !== (r.launch.reach ?? 'LOCAL'))
      for (const r of changed) {
        const target = reach[r.launch.id]
        await launchesApi.setReach(r.launch.id, target, target === 'INTERNET' ? ttl[r.launch.id] : null)
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
        <h2>📡 Share Control</h2>
        {error && <div className="error-banner">{error}</div>}
        <div className="modal-body">
          <p className="muted" style={{ marginTop: 0, lineHeight: 1.6 }}>
            Pick how far each launch reaches: <strong>Local</strong> (this machine only),{' '}
            <strong>Wi-Fi</strong> (<code>&lt;alias&gt;.local</code> on the same network), or{' '}
            <strong>Internet</strong> (a temporary public HTTPS link). Changes apply on Save.
          </p>
          <div style={{ display: 'flex', gap: 8, margin: '4px 0 12px' }}>
            <button onClick={() => setAll('LOCAL')} disabled={busy}>All Local</button>
            <button onClick={() => setAll('WIFI')} disabled={busy}>All Wi-Fi</button>
          </div>
          {rows.length === 0 ? (
            <div className="muted">No shareable launches. Give a launch a named address (alias) to share it.</div>
          ) : (
            <table className="push-control-table">
              <thead>
                <tr>
                  <th style={{ textAlign: 'left' }}>Launch</th>
                  <th style={{ textAlign: 'left' }}>Reach</th>
                  <th style={{ textAlign: 'left', width: 130 }}>Link lifetime</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(r => {
                  const id = r.launch.id
                  const current = reach[id] ?? 'LOCAL'
                  return (
                    <tr key={id} style={r.groupStart ? { borderTop: '2px solid var(--border)' } : undefined}>
                      <td>
                        <div>{r.projectName}</div>
                        <div className="muted" style={{ fontSize: '.85em' }}>
                          {r.launch.name} · {r.launch.alias}.localhost
                        </div>
                      </td>
                      <td>
                        <span className="reach-toggle" role="group" aria-label="Reach">
                          {REACH_OPTIONS.map(o => (
                            <button
                              key={o.value}
                              type="button"
                              className={`reach-opt${current === o.value ? ' active' : ''}`}
                              title={o.title}
                              disabled={busy}
                              onClick={() => setReach(s => ({ ...s, [id]: o.value }))}
                            >
                              {o.label}
                            </button>
                          ))}
                        </span>
                      </td>
                      <td>
                        {current === 'INTERNET' ? (
                          <select
                            value={ttl[id] === null ? 'null' : String(ttl[id])}
                            disabled={busy}
                            onChange={e => setTtl(s => ({ ...s, [id]: e.target.value === 'null' ? null : Number(e.target.value) }))}
                            style={{ width: 'auto' }}
                          >
                            {TTL_OPTIONS.map(o => (
                              <option key={o.label} value={o.minutes === null ? 'null' : String(o.minutes)}>{o.label}</option>
                            ))}
                          </select>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                    </tr>
                  )
                })}
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
