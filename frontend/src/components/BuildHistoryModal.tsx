import { useCallback, useEffect, useState } from 'react'
import { extractError, launchesApi } from '../api'
import type { BuildRecordDto, BuildStatus, LaunchDto, ProjectCommandDto, ProjectDto } from '../types'
import { formatBuildTime } from './BuildBadge'

interface Props {
  project: ProjectDto
  launch: LaunchDto
  busy: boolean
  onRunCommand: (p: ProjectDto, command: ProjectCommandDto) => void
  onStopCommand: (p: ProjectDto, command: ProjectCommandDto) => void
  onClose: () => void
}

const STATUS_LABEL: Record<BuildStatus, string> = {
  RUNNING: 'Running',
  SUCCESS: 'Success',
  FAILED: 'Failed',
  INTERRUPTED: 'Interrupted',
  CANCELLED: 'Cancelled',
  TIMED_OUT: 'Timed out',
}

function duration(r: BuildRecordDto): string {
  if (!r.finishedAt) return '-'
  const s = Math.max(0, Math.round((new Date(r.finishedAt).getTime() - new Date(r.startedAt).getTime()) / 1000))
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m ${s % 60}s`
}

function resultText(r: BuildRecordDto): string {
  const label = STATUS_LABEL[r.status]
  return r.status === 'FAILED' && r.exitCode != null ? `${label} (exit ${r.exitCode})` : label
}

export function BuildHistoryModal({ project, launch, busy, onRunCommand, onStopCommand, onClose }: Readonly<Props>) {
  const [records, setRecords] = useState<BuildRecordDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setRecords(await launchesApi.builds(launch.id))
      setError(null)
    } catch (e) {
      setError(extractError(e))
    }
  }, [launch.id])

  useEffect(() => {
    load()
    const t = setInterval(load, 3000)
    return () => clearInterval(t)
  }, [load])

  const buildCommands = (project.commands ?? []).filter(c => c.buildForLaunchId === launch.id)
  const anyRunning = (project.launches ?? []).some(l => l.status === 'RUNNING' || l.status === 'ATTACHED')
  const head = launch.build?.headSha

  return (
    <div className="modal-backdrop">
      <div className="modal" onClick={e => e.stopPropagation()} style={{ width: 900 }}>
        <h2>{project.name} / {launch.name} — Build History</h2>
        {error && <div className="error-banner">{error}</div>}
        <div className="modal-body">
          {records === null && !error && <div className="empty">Loading…</div>}
          {records !== null && records.length === 0 && (
            <div className="empty">No builds recorded yet. Run a build command to create a baseline.</div>
          )}
          {records !== null && records.length > 0 && (
            <table className="build-history-table">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Command</th>
                  <th>Commit</th>
                  <th>Branch</th>
                  <th>Result</th>
                  <th>Duration</th>
                </tr>
              </thead>
              <tbody>
                {records.map(r => {
                  const current = r.status === 'SUCCESS' && !!head && !!r.commitSha && r.commitSha.startsWith(head)
                  return (
                    <tr key={r.id} className={current ? 'build-current' : undefined}
                        title={current ? 'Built from the current HEAD' : undefined}>
                      <td>{formatBuildTime(r.startedAt)}</td>
                      <td>{r.commandName}</td>
                      <td>
                        {r.commitSha ? <code>{r.commitSha.slice(0, 7)}</code> : '-'}
                        {r.commitSubject && <span className="muted"> {r.commitSubject}</span>}
                      </td>
                      <td>{r.branch ?? '-'}</td>
                      <td><span className={`build-result ${r.status}`}>{resultText(r)}</span></td>
                      <td>{duration(r)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </div>
        <div className="form-actions">
          <button onClick={onClose}>Close</button>
          {buildCommands.map(cmd => {
            if (cmd.running) {
              return (
                <button
                  key={cmd.id}
                  className="danger"
                  title={`Stop ${cmd.name} (kills its process tree)`}
                  onClick={() => onStopCommand(project, cmd)}
                >
                  ■ Stop {cmd.name}
                </button>
              )
            }
            const blocked = cmd.requireStopped && anyRunning
            return (
              <button
                key={cmd.id}
                className="primary"
                disabled={busy || blocked}
                title={blocked ? 'Stop all launches before running this command' : `Run: ${cmd.command}`}
                onClick={() => onRunCommand(project, cmd)}
              >
                Run {cmd.name}
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
