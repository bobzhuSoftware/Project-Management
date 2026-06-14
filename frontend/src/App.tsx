import { useCallback, useEffect, useState } from 'react'
import { extractError, gitApi, projectsApi } from './api'
import type { GitStatusDto, ProjectDto } from './types'
import { ProjectTable } from './components/ProjectTable'
import { ProjectFormModal } from './components/ProjectFormModal'
import { LogsDrawer } from './components/LogsDrawer'
import { GitSyncModal } from './components/GitSyncModal'

export function App() {
  const [projects, setProjects] = useState<ProjectDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [editing, setEditing] = useState<ProjectDto | null>(null)
  const [showForm, setShowForm] = useState(false)
  const [logsFor, setLogsFor] = useState<ProjectDto | null>(null)
  const [syncFor, setSyncFor] = useState<ProjectDto | null>(null)
  const [gitStatus, setGitStatus] = useState<Record<string, GitStatusDto | undefined>>({})
  const [gitLoading, setGitLoading] = useState<Record<string, boolean>>({})

  const refresh = useCallback(async () => {
    try {
      const data = await projectsApi.list()
      setProjects(data)
      setError(null)
    } catch (e) {
      setError(extractError(e))
    }
  }, [])

  const fetchGitStatus = useCallback(async (id: string, refresh = false) => {
    setGitLoading(s => ({ ...s, [id]: true }))
    try {
      const s = await gitApi.status(id, refresh)
      setGitStatus(prev => ({ ...prev, [id]: s }))
    } catch (e) {
      setGitStatus(prev => ({
        ...prev,
        [id]: {
          repo: false, hasUpstream: false, ahead: 0, behind: 0,
          staged: 0, modified: 0, untracked: 0, conflicting: 0,
          clean: false, inSync: false, checkedAt: new Date().toISOString(),
          error: extractError(e),
        },
      }))
    } finally {
      setGitLoading(s => ({ ...s, [id]: false }))
    }
  }, [])

  const refreshAllGit = useCallback((ids: string[], force = false) => {
    ids.forEach(id => { fetchGitStatus(id, force) })
  }, [fetchGitStatus])

  useEffect(() => {
    refresh()
    const t = setInterval(refresh, 3000)
    return () => clearInterval(t)
  }, [refresh])

  // Fetch git status once whenever the set of projects changes (initial load,
  // create, delete). No background polling — refresh is user-driven.
  const projectIdsKey = projects.map(p => p.id).sort().join(',')
  useEffect(() => {
    const ids = projectIdsKey ? projectIdsKey.split(',') : []
    if (ids.length === 0) return
    refreshAllGit(ids, true)
  }, [projectIdsKey, refreshAllGit])

  const handleStart = async (p: ProjectDto) => {
    setBusyId(p.id); setError(null)
    try { await projectsApi.start(p.id); await refresh(); fetchGitStatus(p.id, true) }
    catch (e) { setError(extractError(e)) }
    finally { setBusyId(null) }
  }
  const handleStop = async (p: ProjectDto) => {
    setBusyId(p.id); setError(null)
    try { await projectsApi.stop(p.id); await refresh(); fetchGitStatus(p.id, true) }
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

  const handleReorder = async (orderedIds: string[]) => {
    try {
      await projectsApi.reorder(orderedIds)
      await refresh()
    } catch (e) {
      setError(extractError(e))
    }
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
            gitStatus={gitStatus}
            gitLoading={gitLoading}
            onStart={handleStart}
            onStop={handleStop}
            onEdit={handleEdit}
            onDelete={handleDelete}
            onLogs={setLogsFor}
            onSync={setSyncFor}
            onGitRefresh={(p) => fetchGitStatus(p.id, true)}
            onReorder={handleReorder}
          />
        )}
      </div>
      {showForm && (
        <ProjectFormModal project={editing} onClose={handleFormClose} />
      )}
      {logsFor && (
        <LogsDrawer project={logsFor} onClose={() => setLogsFor(null)} />
      )}
      {syncFor && (
        <GitSyncModal
          project={syncFor}
          status={gitStatus[syncFor.id] ?? null}
          onClose={(changed) => {
            const id = syncFor.id
            setSyncFor(null)
            if (changed) fetchGitStatus(id, true)
          }}
        />
      )}
    </div>
  )
}
