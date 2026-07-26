import { useEffect, useRef } from 'react'

export interface DialogState {
  variant: 'success' | 'error'
  title: string
  message: string
  detail?: string
}

interface Props extends DialogState {
  onClose: () => void
}

export function MessageDialog({ variant, title, message, detail, onClose }: Props) {
  const okRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    okRef.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' || e.key === 'Enter') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className={`modal message-dialog ${variant}`} onClick={e => e.stopPropagation()}>
        <h2>
          <span className="message-dialog-icon">{variant === 'success' ? '✓' : '⚠'}</span>
          {title}
        </h2>
        <div className="modal-body">
          <p className="message-dialog-text">{message}</p>
          {detail && <pre className="message-dialog-detail">{detail}</pre>}
        </div>
        <div className="form-actions">
          <button ref={okRef} className="primary" onClick={onClose}>OK</button>
        </div>
      </div>
    </div>
  )
}
