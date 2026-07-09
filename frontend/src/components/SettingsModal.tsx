import { useEffect, useState } from 'react'
import { extractError, settingsApi } from '../api'

interface Props {
  onClose: () => void
}

export function SettingsModal({ onClose }: Props) {
  const [javaHome, setJavaHome] = useState('')
  const [nodeHome, setNodeHome] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    settingsApi.get()
      .then(s => {
        setJavaHome(s.javaHome ?? '')
        setNodeHome(s.nodeHome ?? '')
      })
      .catch(e => setError(extractError(e)))
      .finally(() => setLoading(false))
  }, [])

  const save = async () => {
    setBusy(true); setError(null)
    try {
      await settingsApi.save({ javaHome: javaHome.trim() || null, nodeHome: nodeHome.trim() || null })
      onClose()
    } catch (e) {
      setError(extractError(e))
    } finally {
      setBusy(false)
    }
  }

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') save()
    if (e.key === 'Escape') onClose()
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()} onKeyDown={handleKey}>
        <h2>⚙ Settings</h2>
        {error && <div className="error-banner">{error}</div>}
        <div className="modal-body">
          <div className="form-row">
            <label>JAVA_HOME override</label>
            <input
              value={loading ? '' : javaHome}
              onChange={e => setJavaHome(e.target.value)}
              placeholder="e.g. C:\Users\bob.zhu\jdk-17.0.19+10  (leave blank = system default)"
              disabled={loading}
              autoFocus
            />
          </div>
          <div className="form-row">
            <label>NODE_HOME override</label>
            <input
              value={loading ? '' : nodeHome}
              onChange={e => setNodeHome(e.target.value)}
              placeholder="e.g. C:\Program Files\nodejs  (leave blank = system default)"
              disabled={loading}
            />
          </div>
          <p className="muted" style={{ marginTop: 8, lineHeight: 1.6 }}>
            When set, <strong>JAVA_HOME</strong> / <strong>NODE_HOME</strong> and <strong>PATH</strong> are overridden for every
            managed project at launch. Leave blank to use the system default.
          </p>
        </div>
        <div className="form-actions">
          <button onClick={onClose} disabled={busy}>Cancel</button>
          <button className="primary" onClick={save} disabled={busy || loading}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}
