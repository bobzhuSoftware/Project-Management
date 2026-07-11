import { useCallback, useEffect, useMemo, useState } from 'react'
import { extractError, gitApi, projectsApi } from './api'
import type { GitStatusDto, ProjectDto } from './types'
import { ProjectTable } from './components/ProjectTable'
import { ProjectFormModal } from './components/ProjectFormModal'
import { LogsDrawer } from './components/LogsDrawer'
import { GitSyncModal } from './components/GitSyncModal'
import { SettingsModal } from './components/SettingsModal'
import { Sidebar, categoryTitle, type CategoryFilter, type SidebarMode } from './components/Sidebar'

const VALID_CATEGORIES: CategoryFilter[] = ['ALL', 'APPLICATION', 'DATABASE', 'SCRIPT', 'OTHER']
const SIDEBAR_MODE_KEY = 'pm.sidebarMode'

function readHashCategory(): CategoryFilter {
  const h = window.location.hash.replace(/^#\/?/, '').toUpperCase()
  return (VALID_CATEGORIES as string[]).includes(h) ? (h as CategoryFilter) : 'ALL'
}

function readSidebarMode(): SidebarMode {
  const v = localStorage.getItem(SIDEBAR_MODE_KEY)
  return v === 'rail' ? 'rail' : 'expanded'
}

export function App() {
  const [projects, setProjects] = useState<ProjectDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [editing, setEditing] = useState<ProjectDto | null>(null)
  const [showForm, setShowForm] = useState(false)
  const [logsFor, setLogsFor] = useState<ProjectDto | null>(null)
  const [gitModal, setGitModal] = useState<{ project: ProjectDto; mode: 'sync' | 'changes' } | null>(null)
  const [gitStatus, setGitStatus] = useState<Record<string, GitStatusDto | undefined>>({})
  const [gitLoading, setGitLoading] = useState<Record<string, boolean>>({})
  const [activeCategory, setActiveCategory] = useState<CategoryFilter>(readHashCategory())
  const [sidebarMode, setSidebarMode] = useState<SidebarMode>(readSidebarMode())
  const [sidebarFloating, setSidebarFloating] = useState(false)
  const [showSettings, setShowSettings] = useState(false)

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

  const handleOpenFolder = async (p: ProjectDto) => {
    setError(null)
    try { await projectsApi.openFolder(p.id) }
    catch (e) { setError(extractError(e)) }
  }

  // Keep URL hash in sync with active category so refresh preserves the view.
  useEffect(() => {
    const desired = activeCategory === 'ALL' ? '' : `#/${activeCategory.toLowerCase()}`
    if (window.location.hash !== desired) {
      window.history.replaceState(null, '', desired || window.location.pathname + window.location.search)
    }
  }, [activeCategory])

  useEffect(() => {
    const onHashChange = () => setActiveCategory(readHashCategory())
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  const visibleProjects = useMemo(
    () => activeCategory === 'ALL' ? projects : projects.filter(p => p.category === activeCategory),
    [projects, activeCategory],
  )

  useEffect(() => { localStorage.setItem(SIDEBAR_MODE_KEY, sidebarMode) }, [sidebarMode])

  return (
    <div className="app">
      <Sidebar
        active={activeCategory}
        onSelect={setActiveCategory}
        projects={projects}
        mode={sidebarMode}
        floating={sidebarFloating}
        onCollapse={() => setSidebarMode('rail')}
        onExpandPinned={() => { setSidebarMode('expanded'); setSidebarFloating(false) }}
        onOpenFloating={() => setSidebarFloating(true)}
        onCloseFloating={() => setSidebarFloating(false)}
        onOpenSettings={() => setShowSettings(true)}
      />
      <div className="app-main">
        <div className="header">
          <h1>{categoryTitle(activeCategory)}</h1>
          <button className="primary" onClick={handleNew}>+ New {activeCategory === 'ALL' ? 'Project' : categoryTitle(activeCategory).replace(/s$/, '')}</button>
        </div>
        <div className="main">
          {error && <div className="error-banner">{error}</div>}
          {visibleProjects.length === 0 ? (
            <div className="empty">
              {projects.length === 0
                ? 'No projects yet. Click "New Project" to register one.'
                : `No ${categoryTitle(activeCategory).toLowerCase()} yet.`}
            </div>
          ) : (
            <ProjectTable
              projects={visibleProjects}
              busyId={busyId}
              gitStatus={gitStatus}
              gitLoading={gitLoading}
              onStart={handleStart}
              onStop={handleStop}
              onEdit={handleEdit}
              onDelete={handleDelete}
              onLogs={setLogsFor}
              onSync={(p) => setGitModal({ project: p, mode: 'sync' })}
              onShowChanges={(p) => setGitModal({ project: p, mode: 'changes' })}
              onGitRefresh={(p) => fetchGitStatus(p.id, true)}
              onReorder={handleReorder}
              onOpenFolder={handleOpenFolder}
            />
          )}
        </div>
      </div>
      {showForm && (
        <ProjectFormModal
          project={editing}
          defaultCategory={activeCategory === 'ALL' ? 'APPLICATION' : activeCategory}
          onClose={handleFormClose}
        />
      )}
      {logsFor && (
        <LogsDrawer project={logsFor} onClose={() => setLogsFor(null)} />
      )}
      {gitModal && (
        <GitSyncModal
          project={gitModal.project}
          status={gitStatus[gitModal.project.id] ?? null}
          mode={gitModal.mode}
          onGoSync={() => setGitModal({ project: gitModal.project, mode: 'sync' })}
          onRefresh={() => fetchGitStatus(gitModal.project.id, true)}
          onClose={(changed) => {
            const id = gitModal.project.id
            setGitModal(null)
            if (changed) fetchGitStatus(id, true)
          }}
        />
      )}
      {showSettings && (
        <SettingsModal onClose={() => setShowSettings(false)} />
      )}
    </div>
  )
}
