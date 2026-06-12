import { useEffect, useRef, useState } from 'react'
import type { ProjectDto } from '../types'
import { logsApi, type LogFileEntry } from '../api'

interface Props {
  project: ProjectDto
  onClose: () => void
}

type Tab = 'live' | 'history'

function formatSize(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

export function LogsDrawer({ project, onClose }: Props) {
  const [tab, setTab] = useState<Tab>('live')
  const [lines, setLines] = useState<string[]>([])
  const [paused, setPaused] = useState(false)
  const [history, setHistory] = useState<LogFileEntry[]>([])
  const [selectedFile, setSelectedFile] = useState<string | null>(null)
  const [historyContent, setHistoryContent] = useState<string>('')
  const [historyLoading, setHistoryLoading] = useState(false)
  const paneRef = useRef<HTMLDivElement>(null)
  const pausedRef = useRef(false)

  useEffect(() => { pausedRef.current = paused }, [paused])

  // Live SSE — only active on 'live' tab.
  useEffect(() => {
    if (tab !== 'live') return
    setLines([])
    const es = new EventSource(`/api/projects/${project.id}/logs/stream`)
    es.addEventListener('log', (ev: MessageEvent) => {
      setLines(prev => {
        const next = prev.length > 5000 ? prev.slice(prev.length - 5000) : prev.slice()
        next.push(ev.data as string)
        return next
      })
    })
    es.onerror = () => { /* server closed or no live process */ }
    return () => es.close()
  }, [project.id, tab])

  // Load history file list when entering history tab.
  useEffect(() => {
    if (tab !== 'history') return
    logsApi.history(project.id).then(setHistory).catch(() => setHistory([]))
  }, [project.id, tab])

  // Load selected file content.
  useEffect(() => {
    if (tab !== 'history' || !selectedFile) { setHistoryContent(''); return }
    setHistoryLoading(true)
    logsApi.historyContent(project.id, selectedFile)
      .then(text => setHistoryContent(text))
      .catch(e => setHistoryContent(`Failed to load: ${e}`))
      .finally(() => setHistoryLoading(false))
  }, [project.id, selectedFile, tab])

  useEffect(() => {
    if (tab === 'live' && !pausedRef.current && paneRef.current) {
      paneRef.current.scrollTop = paneRef.current.scrollHeight
    }
  }, [lines, tab])

  const copyAll = () => {
    const text = tab === 'live' ? lines.join('\n') : historyContent
    navigator.clipboard.writeText(text).catch(() => {})
  }

  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} />
      <div className="drawer">
        <div className="drawer-header">
          <h3>Logs — {project.name} {project.pid ? `(pid ${project.pid})` : ''}</h3>
          <div className="actions">
            {tab === 'live' && (
              <>
                <button onClick={() => setPaused(p => !p)}>{paused ? 'Resume' : 'Pause'}</button>
                <button onClick={() => setLines([])}>Clear</button>
              </>
            )}
            {tab === 'history' && selectedFile && (
              <a className="btn-link" href={logsApi.historyDownloadUrl(project.id, selectedFile)}>Download</a>
            )}
            <button onClick={copyAll}>Copy</button>
            <button onClick={onClose}>Close</button>
          </div>
        </div>
        <div className="drawer-tabs">
          <button
            className={tab === 'live' ? 'tab active' : 'tab'}
            onClick={() => setTab('live')}>Live</button>
          <button
            className={tab === 'history' ? 'tab active' : 'tab'}
            onClick={() => { setTab('history'); setSelectedFile(null) }}>History</button>
        </div>
        {tab === 'live' && (
          <div className="log-pane" ref={paneRef}>
            {lines.length === 0
              ? <pre className="muted">Waiting for logs… (no output yet, or no live process — try the History tab)</pre>
              : <pre>{lines.join('\n')}</pre>}
          </div>
        )}
        {tab === 'history' && (
          <div className="log-history">
            <div className="log-history-list">
              {history.length === 0
                ? <div className="muted">No archived logs yet.</div>
                : history.map(f => (
                    <div
                      key={f.filename}
                      className={selectedFile === f.filename ? 'log-history-item selected' : 'log-history-item'}
                      onClick={() => setSelectedFile(f.filename)}>
                      <div className="filename">{f.filename}</div>
                      <div className="meta">{formatSize(f.size)} · {new Date(f.modifiedAt).toLocaleString()}</div>
                    </div>
                  ))}
            </div>
            <div className="log-pane log-history-content">
              {!selectedFile
                ? <pre className="muted">Pick a file on the left to view its contents.</pre>
                : historyLoading
                  ? <pre className="muted">Loading…</pre>
                  : <pre>{historyContent}</pre>}
            </div>
          </div>
        )}
      </div>
    </>
  )
}
