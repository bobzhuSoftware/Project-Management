import { useEffect, useState } from 'react'
import type { GitFileChange, GitStatusDto, GitSyncResultDto, ProjectDto } from '../types'
import { extractError, gitApi } from '../api'

interface Props {
  project: ProjectDto
  status: GitStatusDto | null
  mode?: 'sync' | 'changes' | 'pull'
  onClose: (changed: boolean) => void
  onGoSync?: () => void
  onRefresh?: () => void
}

function shortTag(t: string): string {
  switch (t) {
    case 'ADDED': return 'A'
    case 'MODIFIED': return 'M'
    case 'DELETED': return 'D'
    case 'RENAMED': return 'R'
    case 'UNTRACKED': return '?'
    case 'CONFLICT': return 'C'
    default: return '•'
  }
}

function typeLabel(t: string): string {
  switch (t) {
    case 'ADDED': return 'Added'
    case 'MODIFIED': return 'Modified'
    case 'DELETED': return 'Deleted'
    case 'RENAMED': return 'Renamed'
    case 'UNTRACKED': return 'Untracked'
    case 'CONFLICT': return 'Conflict'
    default: return t
  }
}

function diffLineClass(line: string): string {
  if (line.startsWith('+') && !line.startsWith('+++')) return 'diff-add'
  if (line.startsWith('-') && !line.startsWith('---')) return 'diff-del'
  if (line.startsWith('@@')) return 'diff-hunk'
  if (line.startsWith('diff ') || line.startsWith('index ') ||
      line.startsWith('+++') || line.startsWith('---') ||
      line.startsWith('new file') || line.startsWith('deleted file') ||
      line.startsWith('similarity') || line.startsWith('rename ')) return 'diff-meta'
  return 'diff-ctx'
}

export function GitSyncModal({ project, status, mode = 'sync', onClose, onGoSync, onRefresh }: Readonly<Props>) {
  const [message, setMessage] = useState('chore: sync from Project Management')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<GitSyncResultDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<GitFileChange | null>(null)
  const [diffText, setDiffText] = useState<string>('')
  const [diffLoading, setDiffLoading] = useState(false)
  const [diffError, setDiffError] = useState<string | null>(null)
  const [incoming, setIncoming] = useState<GitFileChange[]>([])
  const [incomingLoading, setIncomingLoading] = useState(false)
  const [incomingError, setIncomingError] = useState<string | null>(null)

  // In pull mode, preview the files a fast-forward would bring in (HEAD..@{u}).
  useEffect(() => {
    if (mode !== 'pull' || !status || status.behind <= 0 || status.conflicting > 0) return
    let cancelled = false
    setIncomingLoading(true); setIncomingError(null)
    gitApi.incoming(project.id)
      .then(list => { if (!cancelled) setIncoming(list) })
      .catch(e => { if (!cancelled) setIncomingError(extractError(e)) })
      .finally(() => { if (!cancelled) setIncomingLoading(false) })
    return () => { cancelled = true }
  }, [mode, project.id, status?.behind, status?.conflicting])

  const blockedByBehind = !!status && status.behind > 0
  const blockedByConflicts = !!status && status.conflicting > 0
  const pushDisabled = !project.pushEnabled

  const openDiff = async (f: GitFileChange) => {
    setSelected(f)
    setDiffLoading(true); setDiffError(null); setDiffText('')
    try {
      const r = await gitApi.diff(project.id, f.path, f.staged)
      if (r.binary) {
        setDiffText('Binary file — no textual diff available.')
      } else {
        setDiffText(r.diff.trim().length ? r.diff : '(no changes to display)')
      }
    } catch (e) {
      setDiffError(extractError(e))
    } finally {
      setDiffLoading(false)
    }
  }

  const openIncomingDiff = async (f: GitFileChange) => {
    setSelected(f)
    setDiffLoading(true); setDiffError(null); setDiffText('')
    try {
      const r = await gitApi.incomingDiff(project.id, f.path)
      if (r.binary) {
        setDiffText('Binary file — no textual diff available.')
      } else {
        setDiffText(r.diff.trim().length ? r.diff : '(no changes to display)')
      }
    } catch (e) {
      setDiffError(extractError(e))
    } finally {
      setDiffLoading(false)
    }
  }

  const handleSync = async () => {
    setBusy(true); setError(null); setResult(null)
    try {
      const r = await gitApi.sync(project.id, message)
      setResult(r)
    } catch (e) {
      setError(extractError(e))
    } finally {
      setBusy(false)
    }
  }

  const handlePull = async (force = false) => {
    setBusy(true); setError(null); setResult(null)
    try {
      const r = await gitApi.pull(project.id, force)
      setResult(r)
      if (!r.success) setError(r.message ?? 'Pull failed')
      if (onRefresh) onRefresh()
    } catch (e) {
      setError(extractError(e))
    } finally {
      setBusy(false)
    }
  }

  const handleForcePull = () => {
    const ok = window.confirm(
      'Force pull will discard your local changes to the blocking file(s) and '
      + 'overwrite them with the remote version. Continue?'
    )
    if (ok) handlePull(true)
  }

  const overwriteBlocked =
    !!result && !result.success && (result.message ?? '').includes('would be overwritten')

  const handleClose = () => onClose(!!result?.success)

  if (mode === 'pull') {
    const canPull = !!status && status.repo && !status.error && status.behind > 0 && status.conflicting === 0
    const done = !!result?.success
    const showIncoming = !!status && status.behind > 0 && status.conflicting === 0 && !done
    return (
      <div className="modal-backdrop">
        <div className="modal git-changes-modal" onClick={e => e.stopPropagation()} style={{ width: 900 }}>
          <h2>Pull — {project.name}</h2>

          {status && (
            <div className="git-changes-meta">
              <span className="muted">Branch:</span> {status.branch ?? '-'}
              <span className="dot"> · </span>
              <span className="muted">Remote:</span> {status.remoteUrl ?? '-'}
              <span className="dot"> · </span>
              <span className="muted">vs remote:</span> ahead {status.ahead}, behind {status.behind}
            </div>
          )}

          {showIncoming && (
            <div className="git-sync-output">
              Remote has {status!.behind} new commit(s). Review the incoming changes below, then pull to fast-forward.
            </div>
          )}
          {status && status.conflicting > 0 && (
            <div className="error-banner">
              Repository has unresolved merge conflicts. Resolve them before pulling.
            </div>
          )}

          {showIncoming && (
            <div className="git-changes-body">
              <div className="git-file-list">
                {incomingLoading && <div className="empty">Loading incoming changes…</div>}
                {!incomingLoading && incomingError && <div className="error-banner">{incomingError}</div>}
                {!incomingLoading && !incomingError && incoming.length === 0 && (
                  <div className="empty">No file changes to preview.</div>
                )}
                {!incomingLoading && !incomingError && incoming.length > 0 && (
                  <div className="git-file-group">
                    <div className="git-file-group-title">Incoming — {incoming.length}</div>
                    <ul>
                      {incoming.map(f => {
                        const active = selected?.path === f.path
                        return (
                          <li key={`in:${f.path}`} className={`git-file${active ? ' active' : ''}`}>
                            <button type="button" className="git-file-btn" onClick={() => openIncomingDiff(f)}>
                              <span className={`git-file-tag tag-${f.type.toLowerCase()}`} title={typeLabel(f.type)}>{shortTag(f.type)}</span>
                              <span className="git-file-path" title={f.path}>{f.path}</span>
                            </button>
                          </li>
                        )
                      })}
                    </ul>
                  </div>
                )}
              </div>

              <div className="git-diff-pane">
                {!selected && <div className="empty">Select a file to preview the incoming diff.</div>}
                {selected && diffLoading && <div className="empty">Loading diff…</div>}
                {selected && diffError && <div className="error-banner">{diffError}</div>}
                {selected && !diffLoading && !diffError && (
                  <>
                    <div className="git-diff-head" title={selected.path}>{selected.path}</div>
                    <pre className="git-diff">
                      {diffText.split('\n').map((line, i) => (
                        <div key={i} className={diffLineClass(line)}>{line === '' ? ' ' : line}</div>
                      ))}
                    </pre>
                  </>
                )}
              </div>
            </div>
          )}

          {result && (
            <div className={result.success ? 'git-sync-output ok' : 'git-sync-output fail'}>
              {!result.success && result.message && (
                <div className="fail-msg" style={{ whiteSpace: 'pre-wrap' }}>{result.message}</div>
              )}
              <pre>{result.steps.join('\n')}</pre>
            </div>
          )}
          {error && <div className="error-banner">{error}</div>}

          <div className="form-actions">
            <button onClick={handleClose} disabled={busy}>Close</button>
            {!done && (
              <button
                className="primary"
                onClick={() => handlePull()}
                disabled={busy || !canPull}
                title="Fast-forward pull the behind commit(s) from remote"
              >
                {busy ? 'Pulling…' : `Pull ↓ ${status?.behind ?? 0}`}
              </button>
            )}
            {overwriteBlocked && (
              <button
                className="danger"
                onClick={handleForcePull}
                disabled={busy}
                title="Discard local changes to the blocking file(s) and pull the remote version"
              >
                {busy ? 'Pulling…' : 'Force pull (overwrite)'}
              </button>
            )}
          </div>
        </div>
      </div>
    )
  }

  if (mode === 'changes') {
    const files = status?.files ?? []
    const staged = files.filter(f => f.staged)
    const working = files.filter(f => !f.staged)
    const canSync = !!status && status.repo && !status.error &&
      status.behind === 0 && status.conflicting === 0 && status.hasUpstream && project.pushEnabled
    return (
      <div className="modal-backdrop">
        <div className="modal git-changes-modal" onClick={e => e.stopPropagation()} style={{ width: 900 }}>
          <h2>Changes — {project.name}</h2>

          {status && (
            <div className="git-changes-meta">
              <span className="muted">Branch:</span> {status.branch ?? '-'}
              <span className="dot"> · </span>
              {files.length} change{files.length === 1 ? '' : 's'}
            </div>
          )}

          {pushDisabled && (
            <div className="error-banner">
              🔒 Push is disabled for this project. Enable it in the project settings to sync.
            </div>
          )}

          <div className="git-changes-body">
            <div className="git-file-list">
              {files.length === 0 ? (
                <div className="empty">No local changes.</div>
              ) : (
                <>
                  {staged.length > 0 && (
                    <div className="git-file-group">
                      <div className="git-file-group-title">Staged — {staged.length}</div>
                      <ul>
                        {staged.map(f => {
                          const active = selected?.path === f.path && selected?.staged === true
                          return (
                            <li key={`s:${f.path}`} className={`git-file staged${active ? ' active' : ''}`}>
                              <button type="button" className="git-file-btn" onClick={() => openDiff(f)}>
                                <span className={`git-file-tag tag-${f.type.toLowerCase()}`} title={typeLabel(f.type)}>{shortTag(f.type)}</span>
                                <span className="git-file-path" title={f.path}>{f.path}</span>
                              </button>
                            </li>
                          )
                        })}
                      </ul>
                    </div>
                  )}
                  {working.length > 0 && (
                    <div className="git-file-group">
                      <div className="git-file-group-title">Working tree — {working.length}</div>
                      <ul>
                        {working.map(f => {
                          const active = selected?.path === f.path && selected?.staged === false
                          return (
                            <li key={`w:${f.path}`} className={`git-file unstaged${active ? ' active' : ''}`}>
                              <button type="button" className="git-file-btn" onClick={() => openDiff(f)}>
                                <span className={`git-file-tag tag-${f.type.toLowerCase()}`} title={typeLabel(f.type)}>{shortTag(f.type)}</span>
                                <span className="git-file-path" title={f.path}>{f.path}</span>
                              </button>
                            </li>
                          )
                        })}
                      </ul>
                    </div>
                  )}
                </>
              )}
            </div>

            <div className="git-diff-pane">
              {!selected && <div className="empty">Select a file to view its diff.</div>}
              {selected && diffLoading && <div className="empty">Loading diff…</div>}
              {selected && diffError && <div className="error-banner">{diffError}</div>}
              {selected && !diffLoading && !diffError && (
                <>
                  <div className="git-diff-head" title={selected.path}>{selected.path}</div>
                  <pre className="git-diff">
                    {diffText.split('\n').map((line, i) => (
                      <div key={i} className={diffLineClass(line)}>{line === '' ? ' ' : line}</div>
                    ))}
                  </pre>
                </>
              )}
            </div>
          </div>

          {result && !result.success && (
            <div className="git-sync-output fail">
              {result.message && (
                <div className="fail-msg" style={{ whiteSpace: 'pre-wrap' }}>{result.message}</div>
              )}
              <pre>{result.steps.join('\n')}</pre>
            </div>
          )}

          <div className="form-actions">
            {error && <span className="error-banner" style={{ marginRight: 'auto' }}>{error}</span>}
            {status && status.behind > 0 && (
              <button
                onClick={() => handlePull()}
                disabled={busy}
                title="Fast-forward pull the behind commit(s) from remote"
              >
                {busy ? 'Pulling…' : `Pull ↓ ${status.behind}`}
              </button>
            )}
            {overwriteBlocked && (
              <button
                className="danger"
                onClick={handleForcePull}
                disabled={busy}
                title="Discard local changes to the blocking file(s) and pull the remote version"
              >
                {busy ? 'Pulling…' : 'Force pull (overwrite)'}
              </button>
            )}
            {onRefresh && <button onClick={onRefresh} disabled={busy}>Refresh</button>}
            <button onClick={() => onClose(!!result?.success)} disabled={busy}>Close</button>
            <button
              className="primary"
              disabled={busy || !canSync || files.length === 0}
              title={canSync ? 'Commit and push these changes' : 'Resolve conflicts / pull behind commits first'}
              onClick={() => { if (onGoSync) onGoSync() }}
            >
              Sync…
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="modal-backdrop">
      <div className="modal" onClick={e => e.stopPropagation()} style={{ width: 640 }}>
        <h2>Git Sync — {project.name}</h2>

        {status && (
          <div className="git-modal-status">
            <div><span className="muted">Branch:</span> {status.branch ?? '-'}</div>
            <div><span className="muted">Remote:</span> {status.remoteUrl ?? '-'}</div>
            <div>
              <span className="muted">Local changes:</span>{' '}
              staged {status.staged}, modified {status.modified}, untracked {status.untracked}
              {status.conflicting > 0 && <>, conflicts {status.conflicting}</>}
            </div>
            <div>
              <span className="muted">vs remote:</span>{' '}
              ahead {status.ahead}, behind {status.behind}
              {!status.hasUpstream && <span className="muted"> (no upstream)</span>}
            </div>
            {status.remoteError && (
              <div className="muted" style={{ color: '#c084fc' }}>{status.remoteError}</div>
            )}
          </div>
        )}

        {blockedByConflicts && (
          <div className="error-banner">
            Repository has unresolved merge conflicts. Resolve them manually before syncing.
          </div>
        )}
        {pushDisabled && (
          <div className="error-banner">
            🔒 Push is disabled for this project. Enable it in the project settings to sync to remote.
          </div>
        )}
        {blockedByBehind && !blockedByConflicts && (
          <div className="error-banner">
            Remote has {status!.behind} new commit(s). Pull/merge manually before pushing.
          </div>
        )}

        <div className="form-row">
          <label>Commit message</label>
          <textarea
            value={message}
            onChange={e => setMessage(e.target.value)}
            rows={3}
            disabled={busy || !!result?.success}
            maxLength={500}
          />
        </div>

        {result && (
          <div className={result.success ? 'git-sync-output ok' : 'git-sync-output fail'}>
            {!result.success && result.message && <div className="fail-msg">{result.message}</div>}
            <pre>{result.steps.join('\n')}</pre>
          </div>
        )}
        {error && <div className="error-banner">{error}</div>}

        <div className="form-actions">
          <button onClick={handleClose} disabled={busy}>Close</button>
          {blockedByBehind && !blockedByConflicts && (
            <button
              onClick={() => handlePull()}
              disabled={busy}
              title="Fast-forward pull the behind commit(s) from remote"
            >
              {busy ? 'Pulling…' : `Pull ↓ ${status!.behind} (fast-forward)`}
            </button>
          )}
          {overwriteBlocked && (
            <button
              className="danger"
              onClick={handleForcePull}
              disabled={busy}
              title="Discard local changes to the blocking file(s) and pull the remote version"
            >
              {busy ? 'Pulling…' : 'Force pull (overwrite)'}
            </button>
          )}
          {!result?.success && (
            <button
              className="primary"
              onClick={handleSync}
              disabled={busy || blockedByBehind || blockedByConflicts || pushDisabled}
            >
              {busy ? 'Syncing…' : 'Sync to GitHub'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
