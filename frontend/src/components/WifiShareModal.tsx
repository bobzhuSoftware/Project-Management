import { useEffect, useState } from 'react'
import QRCode from 'qrcode'
import type { LaunchDto } from '../types'

interface Props {
  launch: LaunchDto
  onClose: () => void
}

/** Shows the <alias>.local Wi-Fi address as a scannable QR code for phones on the same network. */
export function WifiShareModal({ launch, onClose }: Props) {
  // Only advertise the real proxy address. When it's absent the LAN listener isn't bound yet, so a
  // bare http://<alias>.local would resolve to :80 (http.sys) and 404 — show a not-ready hint instead.
  const url = launch.wifiAddress ?? ''
  const [dataUrl, setDataUrl] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!url) return
    let active = true
    QRCode.toDataURL(url, { width: 240, margin: 1 })
      .then(d => { if (active) setDataUrl(d) })
      .catch(e => { if (active) setError(String(e)) })
    return () => { active = false }
  }, [url])

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* clipboard may be blocked; ignore */ }
  }

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') onClose()
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()} onKeyDown={handleKey}>
        <h2>📶 Open on your phone</h2>
        <div className="modal-body">
          <p className="muted" style={{ marginTop: 0, lineHeight: 1.6 }}>
            Make sure your phone is on the <strong>same Wi-Fi</strong>, then scan this code or type
            the address. Works best in iOS Safari; Android mDNS support varies.
          </p>
          <div className="wifi-share">
            {!url ? (
              <div className="error-banner">
                Wi-Fi address not ready yet. The LAN proxy hasn’t bound a firewall-allowed port —
                toggle Wi-Fi off and on, or check the backend logs, then reopen this dialog.
              </div>
            ) : error ? (
              <div className="error-banner">Could not render QR: {error}</div>
            ) : dataUrl ? (
              <img className="wifi-qr" src={dataUrl} alt={`QR code for ${url}`} width={240} height={240} />
            ) : (
              <div className="muted">Generating…</div>
            )}
            <div className="wifi-url">
              <code>{url || '—'}</code>
              <button onClick={copy} disabled={!url}>{copied ? 'Copied!' : 'Copy'}</button>
            </div>
          </div>
        </div>
        <div className="form-actions">
          <button className="primary" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  )
}
