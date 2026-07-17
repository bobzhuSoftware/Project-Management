import type { ProjectCategory, ProjectDto } from '../types'

export type CategoryFilter = ProjectCategory | 'ALL'
export type SidebarMode = 'expanded' | 'rail'

interface Item {
  key: CategoryFilter
  label: string
  icon: string
}

const ITEMS: Item[] = [
  { key: 'ALL',         label: 'All',          icon: '⌘' },
  { key: 'APPLICATION', label: 'Applications', icon: '▶' },
  { key: 'DATABASE',    label: 'Databases',    icon: '◉' },
  { key: 'SCRIPT',      label: 'Scripts',      icon: '∼' },
  { key: 'OTHER',       label: 'Other',        icon: '•' },
]

interface Props {
  active: CategoryFilter
  onSelect: (c: CategoryFilter) => void
  projects: ProjectDto[]
  /** Layout mode of the persistent sidebar element. */
  mode: SidebarMode
  /** True when the floating overlay panel is open (only meaningful in rail mode). */
  floating?: boolean
  onCollapse?: () => void
  onExpandPinned?: () => void
  onOpenFloating?: () => void
  onCloseFloating?: () => void
  onOpenSettings?: () => void
  onOpenPushControl?: () => void
}

function NavList({
  active, onSelect, projects, showLabels,
}: {
  active: CategoryFilter
  onSelect: (c: CategoryFilter) => void
  projects: ProjectDto[]
  showLabels: boolean
}) {
  const counts: Record<CategoryFilter, number> = {
    ALL: projects.length, APPLICATION: 0, DATABASE: 0, SCRIPT: 0, OTHER: 0,
  }
  for (const p of projects) counts[p.category] = (counts[p.category] ?? 0) + 1
  return (
    <nav className="sidebar-nav">
      {ITEMS.map(it => (
        <button
          key={it.key}
          className={`sidebar-item${active === it.key ? ' active' : ''}`}
          title={showLabels ? undefined : `${it.label} (${counts[it.key]})`}
          onClick={() => onSelect(it.key)}
        >
          <span className="sidebar-icon">{it.icon}</span>
          {showLabels && <span className="sidebar-label">{it.label}</span>}
          {showLabels && <span className="sidebar-count">{counts[it.key]}</span>}
        </button>
      ))}
    </nav>
  )
}

export function Sidebar(props: Props) {
  const { active, onSelect, projects, mode, floating,
          onCollapse, onExpandPinned, onOpenFloating, onCloseFloating, onOpenSettings, onOpenPushControl } = props

  const isRail = mode === 'rail'
  const running = projects.filter(p => p.status === 'RUNNING' || p.status === 'ATTACHED').length

  // Persistent element
  const persistent = (
    <aside className={`sidebar ${isRail ? 'rail' : 'expanded'}`}>
      <div className="sidebar-brand">
        {isRail ? (
          <button
            className="sidebar-toggle"
            title="Expand sidebar (overlay)"
            onClick={onOpenFloating}
          >≡</button>
        ) : (
          <>
            <span className="sidebar-brand-text">PM</span>
            <button
              className="sidebar-toggle"
              title="Collapse sidebar"
              onClick={onCollapse}
            >‹</button>
          </>
        )}
      </div>
      <NavList active={active} onSelect={onSelect} projects={projects} showLabels={!isRail} />
      {isRail ? (
        <div style={{ padding: '8px 4px', borderTop: '1px solid var(--border)' }}>
          <button
            className="sidebar-item"
            title="Push Control"
            onClick={onOpenPushControl}
            style={{ justifyContent: 'center', padding: '8px 0' }}
          >
            <span className="sidebar-icon">🔐</span>
          </button>
          <button
            className="sidebar-item"
            title="Settings"
            onClick={onOpenSettings}
            style={{ justifyContent: 'center', padding: '8px 0' }}
          >
            <span className="sidebar-icon">⚙</span>
          </button>
        </div>
      ) : (
        <div className="sidebar-footer">
          <div className="muted">Running: <strong>{running}</strong> / {projects.length}</div>
          <button
            className="sidebar-item"
            onClick={onOpenPushControl}
            style={{ marginTop: 6, width: '100%' }}
          >
            <span className="sidebar-icon">🔐</span>
            <span className="sidebar-label">Push Control</span>
          </button>
          <button
            className="sidebar-item"
            onClick={onOpenSettings}
            style={{ marginTop: 6, width: '100%' }}
          >
            <span className="sidebar-icon">⚙</span>
            <span className="sidebar-label">Settings</span>
          </button>
        </div>
      )}
    </aside>
  )

  if (!isRail || !floating) return persistent

  // Floating overlay panel (rail still occupies layout, panel overlays on top of content)
  return (
    <>
      {persistent}
      <div className="sidebar-overlay-backdrop" onClick={onCloseFloating}>
        <aside className="sidebar expanded sidebar-floating" onClick={e => e.stopPropagation()}>
          <div className="sidebar-brand">
            <span className="sidebar-brand-text">PM</span>
            <button
              className="sidebar-toggle"
              title="Pin sidebar open"
              onClick={onExpandPinned}
            >📌</button>
            <button
              className="sidebar-toggle"
              title="Close"
              onClick={onCloseFloating}
            >✕</button>
          </div>
          <NavList
            active={active}
            onSelect={(c) => { onSelect(c); onCloseFloating?.() }}
            projects={projects}
            showLabels
          />
          <div className="sidebar-footer">
            <div className="muted">Running: <strong>{running}</strong> / {projects.length}</div>
            <button
              className="sidebar-item"
              onClick={() => { onOpenPushControl?.(); onCloseFloating?.() }}
              style={{ marginTop: 6, width: '100%' }}
            >
              <span className="sidebar-icon">🔐</span>
              <span className="sidebar-label">Push Control</span>
            </button>
            <button
              className="sidebar-item"
              onClick={() => { onOpenSettings?.(); onCloseFloating?.() }}
              style={{ marginTop: 6, width: '100%' }}
            >
              <span className="sidebar-icon">⚙</span>
              <span className="sidebar-label">Settings</span>
            </button>
          </div>
        </aside>
      </div>
    </>
  )
}

export function categoryTitle(c: CategoryFilter): string {
  switch (c) {
    case 'ALL': return 'All Resources'
    case 'APPLICATION': return 'Applications'
    case 'DATABASE': return 'Databases'
    case 'SCRIPT': return 'Scripts'
    case 'OTHER': return 'Other'
  }
}
