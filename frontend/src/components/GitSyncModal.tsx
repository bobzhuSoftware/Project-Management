import { useState } from 'react'
import type { GitStatusDto, GitSyncResultDto, ProjectDto } from '../types'
import { extractError, gitApi } from '../api'

interface Props {
  project: ProjectDto
  status: GitStatusDto | null
  onClose: (changed: boolean) => void
}

export function GitSyncModal({ project, status, onClose }: Props) {
  const [message, setMessage] = useState('chore: sync from Project Management')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<GitSyncResultDto | null>(null)
  const [error, setError] = useState<string | null>(null)

  const blockedByBehind = !!status && status.behind > 0
  const blockedByConflicts = !!status && status.conflicting > 0

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

  const handleClose = () => onClose(!!result?.success)

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
          </div>
        )}

        {blockedByConflicts && (
          <div className="error-banner">
            Repository has unresolved merge conflicts. Resolve them manually before syncing.
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
          {!result?.success && (
            <button
              className="primary"
              onClick={handleSync}
              disabled={busy || blockedByBehind || blockedByConflicts}
            >
              {busy ? 'Syncing…' : 'Sync to GitHub'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
