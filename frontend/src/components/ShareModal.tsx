import { useEffect, useMemo, useState } from 'react'
import QRCode from 'qrcode'
import type { LaunchDto } from '../types'

interface Props {
  launch: LaunchDto
  /** Create (or replace) the public link with the chosen lifetime; null = no expiry. */
  onCreate: (ttlMinutes: number | null) => Promise<void>
  /** Tear the public link down (reach back to Local). */
  onStop: () => Promise<void>
  onClose: () => void
}

const DURATIONS: { label: string; minutes: number | null }[] = [
  { label: '1 hour', minutes: 60 },
  { label: '8 hours', minutes: 8 * 60 },
  { label: '24 hours', minutes: 24 * 60 },
  { label: '7 days', minutes: 7 * 24 * 60 },
  { label: 'No expiry', minutes: null },
]

function useCountdown(expiresAt?: string | null): string | null {
  const [, tick] = useState(0)
  useEffect(() => {
    if (!expiresAt) return
    const id = setInterval(() => tick(t => t + 1), 1000)
    return () => clearInterval(id)
  }, [expiresAt])
  if (!expiresAt) return null
  const ms = new Date(expiresAt).getTime() - Date.now()
  if (ms <= 0) return 'expired'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${sec}s`
  return `${sec}s`
}

/** Rung 3: create and show a temporary public HTTPS link (Cloudflare quick tunnel) for a launch. */
export function ShareModal({ launch, onCreate, onStop, onClose }: Props) {
  const active = !!launch.shareUrl
  const publicUrl = useMemo(
    () => (launch.shareUrl ? `${launch.shareUrl}?key=${launch.shareKey ?? ''}` : ''),
    [launch.shareUrl, launch.shareKey],
  )
  const [ttl, setTtl] = useState<number | null>(60)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [dataUrl, setDataUrl] = useState<string | null>(null)
  const countdown = useCountdown(launch.shareExpiresAt)

  useEffect(() => {
    if (!publicUrl) { setDataUrl(null); return }
    let on = true
    QRCode.toDataURL(publicUrl, { width: 240, margin: 1 })
      .then(d => { if (on) setDataUrl(d) })
      .catch(() => { if (on) setDataUrl(null) })
    return () => { on = false }
  }, [publicUrl])

  const create = async () => {
    setBusy(true); setError(null)
    try { await onCreate(ttl) }
    catch (e: any) { setError(e?.response?.data?.error ?? e?.response?.data?.message ?? e?.message ?? 'Failed to create link') }
    finally { setBusy(false) }
  }

  const stop = async () => {
    setBusy(true); setError(null)
    try { await onStop() }
    catch (e: any) { setError(e?.response?.data?.error ?? e?.response?.data?.message ?? e?.message ?? 'Failed to stop sharing') }
    finally { setBusy(false) }
  }

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(publicUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* clipboard may be blocked */ }
  }

  const handleKey = (e: React.KeyboardEvent) => { if (e.key === 'Escape') onClose() }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()} onKeyDown={handleKey}>
        <h2>🌐 Share on the Internet</h2>
        <div className="modal-body">
          {!active ? (
            <>
              <p className="muted" style={{ marginTop: 0, lineHeight: 1.6 }}>
                Creates a temporary public <strong>HTTPS</strong> link via Cloudflare. Anyone with the
                link can reach <code>{launch.alias}</code> — no install needed on their side. The link
                is key-gated and dies when you stop sharing or quit.
              </p>
              <label className="field-label">Link stays live for</label>
              <div className="reach-toggle" role="group" aria-label="Link lifetime" style={{ flexWrap: 'wrap' }}>
                {DURATIONS.map(d => (
                  <button
                    key={d.label}
                    type="button"
                    className={`reach-opt${ttl === d.minutes ? ' active' : ''}`}
                    disabled={busy}
                    onClick={() => setTtl(d.minutes)}
                  >
                    {d.label}
                  </button>
                ))}
              </div>
              {ttl === null && (
                <p className="muted" style={{ fontSize: '.85rem' }}>
                  Stays up until you stop it or quit PM — use for trusted partners.
                </p>
              )}
              {error && <div className="error-banner">{error}</div>}
            </>
          ) : (
            <>
              <p className="muted" style={{ marginTop: 0, lineHeight: 1.6 }}>
                Live public link {countdown ? <>— expires in <strong>{countdown}</strong></> : <>— <strong>no expiry</strong></>}.
                Share the whole URL including the <code>key</code>.
              </p>
              <div className="wifi-share">
                {dataUrl && <img className="wifi-qr" src={dataUrl} alt="QR code" width={240} height={240} />}
                <div className="wifi-url">
                  <code>{publicUrl}</code>
                  <button onClick={copy}>{copied ? 'Copied!' : 'Copy'}</button>
                </div>
              </div>
              {error && <div className="error-banner">{error}</div>}
            </>
          )}
        </div>
        <div className="form-actions">
          {!active ? (
            <>
              <button onClick={onClose} disabled={busy}>Cancel</button>
              <button className="primary" onClick={create} disabled={busy}>
                {busy ? 'Creating… (a few seconds)' : 'Create public link'}
              </button>
            </>
          ) : (
            <>
              <button className="danger" onClick={stop} disabled={busy}>{busy ? 'Stopping…' : 'Stop sharing'}</button>
              <button className="primary" onClick={onClose}>Close</button>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
