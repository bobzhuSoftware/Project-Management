import { useCallback, useEffect, useState } from 'react'
import { extractError, projectsApi } from './api'
import type { ProjectDto } from './types'
import { ProjectTable } from './components/ProjectTable'
import { ProjectFormModal } from './components/ProjectFormModal'
import { LogsDrawer } from './components/LogsDrawer'

export function App() {
  const [projects, setProjects] = useState<ProjectDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [editing, setEditing] = useState<ProjectDto | null>(null)
  const [showForm, setShowForm] = useState(false)
  const [logsFor, setLogsFor] = useState<ProjectDto | null>(null)

  const refresh = useCallback(async () => {
    try {
      const data = await projectsApi.list()
      setProjects(data)
      setError(null)
    } catch (e) {
      setError(extractError(e))
    }
  }, [])

  useEffect(() => {
    refresh()
    const t = setInterval(refresh, 3000)
    return () => clearInterval(t)
  }, [refresh])

  const handleStart = async (p: ProjectDto) => {
    setBusyId(p.id); setError(null)
    try { await projectsApi.start(p.id); await refresh() }
    catch (e) { setError(extractError(e)) }
    finally { setBusyId(null) }
  }
  const handleStop = async (p: ProjectDto) => {
    setBusyId(p.id); setError(null)
    try { await projectsApi.stop(p.id); await refresh() }
    catch (e) { setError(extractError(e)) }
    finally { setBusyId(null) }
  }
  const handleDelete = async (p: ProjectDto) => {
    if (!confirm(`Delete "${p.name}"?`)) return
    setBusyId(p.id); setError(null)
    try { await projectsApi.remove(p.id); await refresh() }
    catch (e) { setError(extractError(e)) }
    finally { setBusyId(null) }
  }
  const handleEdit = (p: ProjectDto) => { setEditing(p); setShowForm(true) }
  const handleNew = () => { setEditing(null); setShowForm(true) }
  const handleFormClose = (changed: boolean) => {
    setShowForm(false); setEditing(null)
    if (changed) refresh()
  }

  return (
    <div className="app">
      <div className="header">
        <h1>Project Management</h1>
        <button className="primary" onClick={handleNew}>+ New Project</button>
      </div>
      <div className="main">
        {error && <div className="error-banner">{error}</div>}
        {projects.length === 0 ? (
          <div className="empty">No projects yet. Click "New Project" to register one.</div>
        ) : (
          <ProjectTable
            projects={projects}
            busyId={busyId}
            onStart={handleStart}
            onStop={handleStop}
            onEdit={handleEdit}
            onDelete={handleDelete}
            onLogs={setLogsFor}
          />
        )}
      </div>
      {showForm && (
        <ProjectFormModal project={editing} onClose={handleFormClose} />
      )}
      {logsFor && (
        <LogsDrawer project={logsFor} onClose={() => setLogsFor(null)} />
      )}
    </div>
  )
}
