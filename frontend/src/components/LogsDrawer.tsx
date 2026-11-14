import { useEffect, useRef, useState } from 'react'
import { logSourceApi, type LogFileEntry, type LogKind } from '../api'
import type { ProjectCommandDto } from '../types'

export interface LogSource {
  id: string
  name: string
  pid?: number | null
  kind: LogKind
}

interface Props {
  source: LogSource
  projectName: string
  /** Live state of the command being viewed (kind === 'command'); drives the status line and Stop button. */
  command?: ProjectCommandDto | null
  onStopCommand?: (command: ProjectCommandDto) => void
  onClose: () => void
}

type Tab = 'live' | 'history'

/** Without output for this long a running command is flagged as possibly stuck. */
const IDLE_WARN_MS = 2 * 60 * 1000

function formatSize(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

function formatSpan(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}

export function LogsDrawer({ source, projectName, command, onStopCommand, onClose }: Props) {
  const [tab, setTab] = useState<Tab>('live')
  const [lines, setLines] = useState<string[]>([])
  const [paused, setPaused] = useState(false)
  const [history, setHistory] = useState<LogFileEntry[]>([])
  const [selectedFile, setSelectedFile] = useState<string | null>(null)
  const [historyContent, setHistoryContent] = useState<string>('')
  const [historyLoading, setHistoryLoading] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const paneRef = useRef<HTMLDivElement>(null)
  const pausedRef = useRef(false)

  const running = !!command?.running

  useEffect(() => { pausedRef.current = paused }, [paused])

  // Tick once a second while a command runs so elapsed / idle times stay current.
  useEffect(() => {
    if (!running) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [running])

  // Live SSE — only active on 'live' tab.
  useEffect(() => {
    if (tab !== 'live') return
    setLines([])
    const es = new EventSource(logSourceApi.streamUrl(source.kind, source.id))
    es.addEventListener('log', (ev: MessageEvent) => {
      setLines(prev => {
        const next = prev.length > 5000 ? prev.slice(prev.length - 5000) : prev.slice()
        next.push(ev.data as string)
        return next
      })
    })
    es.onerror = () => { /* server closed or no live process */ }
    return () => es.close()
  }, [source.id, source.kind, tab])

  // Load history file list when entering history tab.
  useEffect(() => {
    if (tab !== 'history') return
    logSourceApi.history(source.kind, source.id).then(setHistory).catch(() => setHistory([]))
  }, [source.id, source.kind, tab])

  // Load selected file content.
  useEffect(() => {
    if (tab !== 'history' || !selectedFile) { setHistoryContent(''); return }
    setHistoryLoading(true)
    logSourceApi.historyContent(source.kind, source.id, selectedFile)
      .then(text => setHistoryContent(text))
      .catch(e => setHistoryContent(`Failed to load: ${e}`))
      .finally(() => setHistoryLoading(false))
  }, [source.id, source.kind, selectedFile, tab])

  useEffect(() => {
    if (tab === 'live' && !pausedRef.current && paneRef.current) {
      paneRef.current.scrollTop = paneRef.current.scrollHeight
    }
  }, [lines, tab])

  const copyAll = () => {
    const text = tab === 'live' ? lines.join('\n') : historyContent
    navigator.clipboard.writeText(text).catch(() => {})
  }

  const downloadVisible = () => {
    const stamp = new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15)
    const base = `${projectName}-${source.name}-${stamp}`.replace(/[\\/:*?"<>|\s]+/g, '_')
    const url = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `${base}.log`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  const idleMs = running && command?.lastOutputAt ? now - new Date(command.lastOutputAt).getTime() : 0
  const maybeStuck = idleMs >= IDLE_WARN_MS

  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} />
      <div className="drawer">
        <div className="drawer-header">
          <h3>Logs — {projectName} / {source.name} {source.pid ? `(pid ${source.pid})` : ''}</h3>
          <div className="actions">
            {running && command && onStopCommand && (
              <button className="danger" onClick={() => onStopCommand(command)}
                title="Kill this command and all of its child processes">■ Stop</button>
            )}
            {tab === 'live' && (
              <>
                <button onClick={() => setPaused(p => !p)} title="Freeze / resume auto-scroll of the log pane">
                  {paused ? 'Resume scroll' : 'Pause scroll'}
                </button>
                <button onClick={() => setLines([])}>Clear</button>
                <button onClick={downloadVisible} disabled={lines.length === 0}
                  title="Save the log lines currently shown (last 5000) as a .log file">Download visible</button>
                <a className="btn-link" href={logSourceApi.downloadLatestUrl(source.kind, source.id)}
                  title="Download the complete on-disk log file">Download full</a>
              </>
            )}
            {tab === 'history' && selectedFile && (
              <a className="btn-link" href={logSourceApi.historyDownloadUrl(source.kind, source.id, selectedFile)}>Download</a>
            )}
            <button onClick={copyAll}>Copy</button>
            <button onClick={onClose}>Close</button>
          </div>
        </div>
        {source.kind === 'command' && command && (
          <div className={`drawer-status${maybeStuck ? ' warn' : ''}`}>
            {running
              ? <>
                  <span className="cmd-running-dot" /> Running
                  {command.startedAt && ` · ${formatSpan(now - new Date(command.startedAt).getTime())}`}
                  {command.lastOutputAt && ` · last output ${formatSpan(idleMs)} ago`}
                  {maybeStuck && ' — no output for a while, it may be stuck. Use Stop to kill it.'}
                </>
              : 'Not running'}
          </div>
        )}
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
              ? <pre className="muted">Waiting for logs… (connecting to stream — if the project isn't running, the most recent log file will be shown)</pre>
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
