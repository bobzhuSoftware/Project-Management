import type { BuildFreshnessDto, LaunchDto } from '../types'

export function formatBuildTime(iso?: string | null): string {
  if (!iso) return '-'
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function badgeFor(b: BuildFreshnessDto): { cls: string; text: string } | null {
  let cls: string
  let text: string
  switch (b.state) {
    case 'BUILDING':
      return { cls: 'build-building', text: 'Building…' }
    case 'FRESH':
      return { cls: 'build-fresh', text: '✓ Up to date' }
    case 'STALE':
      cls = 'build-stale'
      text = b.commitsBehind != null && b.commitsBehind > 0
        ? `⚠ Rebuild needed ↓${b.commitsBehind}`
        : '⚠ Rebuild needed (different commit)'
      break
    case 'NO_RECORD':
      cls = 'build-none'
      text = 'No build record'
      break
    default:
      return null
  }
  if (b.lastResult === 'FAILED') text += ' · last build failed'
  else if (b.lastResult === 'INTERRUPTED') text += ' · last build interrupted'
  else if (b.lastResult === 'CANCELLED') text += ' · last build cancelled'
  else if (b.lastResult === 'TIMED_OUT') text += ' · last build timed out'
  return { cls, text }
}

function badgeTitle(b: BuildFreshnessDto): string {
  const parts = [`Current HEAD ${b.headSha ?? '?'}`]
  if (b.builtSha) {
    const subject = b.builtSubject ? ` "${b.builtSubject}"` : ''
    parts.push(`built at ${b.builtSha}${subject}`)
  }
  if (b.builtAt) parts.push(formatBuildTime(b.builtAt))
  return `${parts.join(' · ')} — click for build history`
}

interface Props {
  launch: LaunchDto
  onClick: (l: LaunchDto) => void
}

/** Build-freshness badge for a launch; renders nothing when no command is configured to build it. */
export function BuildBadge({ launch, onClick }: Readonly<Props>) {
  const b = launch.build
  if (!b) return null
  const badge = badgeFor(b)
  if (!badge) return null
  return (
    <button
      type="button"
      className={`git-badge git-badge-btn ${badge.cls}`}
      title={badgeTitle(b)}
      onClick={(e) => { e.stopPropagation(); onClick(launch) }}
    >
      {badge.text}
    </button>
  )
}
