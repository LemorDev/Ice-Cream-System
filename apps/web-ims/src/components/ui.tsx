import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'

export function OverheadIcon({ kind, className = 'h-5 w-5' }: { kind?: string; className?: string }) {
  const common = { className, fill: 'none', viewBox: '0 0 24 24', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }
  if (kind === 'cashier') return <svg {...common} aria-hidden="true"><path d="M4 20v-1.5a4.5 4.5 0 0 1 4.5-4.5h3a4.5 4.5 0 0 1 4.5 4.5V20" /><circle cx="10" cy="7" r="3" /><path d="M17 11h3m-1.5-1.5V12.5" /></svg>
  if (kind === 'rent') return <svg {...common} aria-hidden="true"><path d="m3 10 9-7 9 7" /><path d="M5 9v11h14V9M9 20v-6h6v6" /></svg>
  if (kind === 'electricity') return <svg {...common} aria-hidden="true"><path d="m13 2-9 12h7l-1 8 9-12h-7l1-8Z" /></svg>
  if (kind === 'water') return <svg {...common} aria-hidden="true"><path d="M12 3s6 6.2 6 11a6 6 0 0 1-12 0c0-4.8 6-11 6-11Z" /><path d="M9 16a3 3 0 0 0 3 2" /></svg>
  return <svg {...common} aria-hidden="true"><path d="M12 5v14m-7-7h14" /></svg>
}

export function EditIcon({ className = 'h-4 w-4' }: { className?: string }) {
  return <svg aria-hidden="true" className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"><path d="M12 20h9" /><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L8 18l-4 1 1-4Z" /></svg>
}

export function DeleteIcon({ className = 'h-4 w-4' }: { className?: string }) {
  return <svg aria-hidden="true" className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h18" /><path d="M8 6V4h8v2m3 0-1 14H6L5 6" /><path d="M10 11v5m4-5v5" /></svg>
}

export function ConfirmationDialog({ title, description, confirmLabel, onCancel, onConfirm, busy = false, confirmDisabled = false, tone = 'danger', children }: {
  title: string
  description: string
  confirmLabel: string
  onCancel: () => void
  onConfirm: () => void
  busy?: boolean
  confirmDisabled?: boolean
  tone?: 'danger' | 'primary'
  children?: ReactNode
}) {
  const titleId = useId()
  const descriptionId = useId()
  const dialogRef = useRef<HTMLElement>(null)
  const onCancelRef = useRef(onCancel)
  const busyRef = useRef(busy)
  onCancelRef.current = onCancel
  busyRef.current = busy

  useEffect(() => {
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const handleKeyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busyRef.current) onCancelRef.current()
      if (event.key !== 'Tab' || !dialogRef.current) return
      const focusable = [...dialogRef.current.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])')]
      if (focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', handleKeyboard)
    return () => {
      document.removeEventListener('keydown', handleKeyboard)
      document.body.style.overflow = previousOverflow
      previouslyFocused?.focus()
    }
  }, [])

  return (
    <div className="fixed inset-0 z-[90] flex items-center justify-center bg-[#18002f]/60 p-4 backdrop-blur-sm" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onCancel() }}>
      <section aria-describedby={descriptionId} aria-labelledby={titleId} aria-modal="true" className="w-full max-w-md overflow-hidden rounded-3xl border border-[#eadcff] bg-[#fffdf8] shadow-[0_30px_90px_rgba(30,0,60,0.38)]" ref={dialogRef} role="alertdialog">
        <div className="p-6 sm:p-7">
          <div className={`flex h-12 w-12 items-center justify-center rounded-2xl ${tone === 'danger' ? 'bg-red-100 text-red-700' : 'bg-[#efe5ff] text-violet-700'}`}>
            <svg aria-hidden="true" className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"><path d="M12 9v4m0 4h.01" /><path d="M10.3 3.7 2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.7a2 2 0 0 0-3.4 0Z" /></svg>
          </div>
          <p className="mt-5 text-xs font-bold uppercase tracking-[0.18em] text-[#7c3aed]">Please confirm</p>
          <h2 className="mt-2 text-xl font-black text-[#220046]" id={titleId}>{title}</h2>
          <p className="mt-2 text-sm leading-6 text-slate-600" id={descriptionId}>{description}</p>
          {children && <div className="mt-5">{children}</div>}
        </div>
        <div className="flex flex-col-reverse gap-2 border-t border-[#eee5f5] bg-white/80 px-6 py-4 sm:flex-row sm:justify-end">
          <Button autoFocus disabled={busy} onClick={onCancel} variant="ghost">Cancel</Button>
          <Button disabled={busy || confirmDisabled} onClick={onConfirm} variant={tone === 'danger' ? 'danger' : 'primary'}>{busy ? 'Working…' : confirmLabel}</Button>
        </div>
      </section>
    </div>
  )
}

export function Button({ children, variant = 'primary', ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' | 'ghost' }) {
  const styles = {
    primary: 'bg-violet-700 text-[#fff8ea] hover:bg-violet-800 shadow-[0_12px_30px_rgba(91,33,182,0.24)]',
    secondary: 'bg-[#2a005c] text-[#fff8ea] hover:bg-[#3a007a]',
    danger: 'bg-red-50 text-red-700 hover:bg-red-100',
    ghost: 'text-[#4b2a7a] hover:bg-[#f5ebff] hover:text-[#220046]',
  }
  const automaticIcon = children === 'Edit' ? <EditIcon /> : children === 'Delete' ? <DeleteIcon /> : null
  return <button {...props} className={`inline-flex items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-50 ${styles[variant]} ${props.className ?? ''}`}>{automaticIcon}{children}</button>
}

export function Input({ label, hint, ...props }: React.InputHTMLAttributes<HTMLInputElement> & { label?: string; hint?: string }) {
  return <label className="block text-sm font-medium text-[#39235f]">{label}{hint && <span className="ml-2 text-xs font-normal text-slate-500">{hint}</span>}<input {...props} className={`mt-1 w-full rounded-lg border border-[#dfd4f3] bg-white px-3 py-2 text-sm outline-none transition focus:border-violet-500 focus:ring-2 focus:ring-[#eadcff] ${props.className ?? ''}`} /></label>
}

export function Select({ label, children, ...props }: React.SelectHTMLAttributes<HTMLSelectElement> & { label?: string }) {
  return <label className="block text-sm font-medium text-[#39235f]">{label}<select {...props} className={`mt-1 w-full rounded-lg border border-[#dfd4f3] bg-white px-3 py-2 text-sm outline-none focus:border-violet-500 focus:ring-2 focus:ring-[#eadcff] ${props.className ?? ''}`}>{children}</select></label>
}

export function Textarea({ label, ...props }: React.TextareaHTMLAttributes<HTMLTextAreaElement> & { label?: string }) {
  return <label className="block text-sm font-medium text-[#39235f]">{label}<textarea {...props} className={`mt-1 w-full rounded-lg border border-[#dfd4f3] bg-white px-3 py-2 text-sm outline-none focus:border-violet-500 focus:ring-2 focus:ring-[#eadcff] ${props.className ?? ''}`} /></label>
}

export function Panel({ title, description, action, children }: { title: string; description?: string; action?: ReactNode; children: ReactNode }) {
  return <section className="min-w-0 overflow-hidden rounded-2xl border border-[#eadcff] bg-white/90 shadow-[0_20px_50px_rgba(60,0,112,0.08)] backdrop-blur"><div className="flex flex-col items-stretch gap-4 border-b border-[#f1e8ff] p-4 sm:flex-row sm:items-start sm:justify-between sm:gap-6 sm:p-5"><div className="min-w-0"><h2 className="font-semibold text-[#240042]">{title}</h2>{description && <p className="mt-1 max-w-3xl text-sm leading-5 text-slate-500">{description}</p>}</div>{action && <div className="min-w-0 w-full sm:w-auto sm:max-w-full">{action}</div>}</div><div className="min-w-0 p-4 sm:p-5">{children}</div></section>
}

export function Notice({ children, tone = 'error' }: { children: ReactNode; tone?: 'error' | 'success' | 'info' }) {
  const styles = { error: 'border-red-200 bg-red-50 text-red-700', success: 'border-emerald-200 bg-emerald-50 text-emerald-700', info: 'border-sky-200 bg-sky-50 text-sky-700' }
  return <p className={`rounded-lg border px-3 py-2 text-sm ${styles[tone]}`} role={tone === 'error' ? 'alert' : 'status'}>{children}</p>
}

export function EmptyState({ title, description }: { title: string; description: string }) {
  return <div className="rounded-xl border border-dashed border-[#d9c2ff] bg-[#fbf7ff] px-5 py-10 text-center"><p className="font-semibold text-[#39235f]">{title}</p><p className="mt-1 text-sm text-slate-500">{description}</p></div>
}

export function LoadingState({ label = 'Loading data…' }: { label?: string }) {
  return <div className="flex items-center gap-3 rounded-xl border border-[#eadcff] bg-white p-6 text-sm text-slate-500 shadow-sm"><span className="h-4 w-4 animate-spin rounded-full border-2 border-[#efe5ff] border-t-violet-700" />{label}</div>
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'sellable' | 'packaging' | 'raw' }) {
  const styles = { neutral: 'bg-[#efe5ff] text-[#4b2a7a]', success: 'bg-emerald-100 text-emerald-700', warning: 'bg-amber-100 text-amber-700', danger: 'bg-red-100 text-red-700', sellable: 'bg-violet-100 text-violet-800', packaging: 'bg-amber-100 text-amber-800', raw: 'bg-teal-100 text-teal-800' }
  return <span className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ${styles[tone]}`}>{children}</span>
}

export function Table({ children }: { children: ReactNode }) {
  return <div className="min-w-0 max-w-full overflow-x-auto overscroll-x-contain"><table className="w-full min-w-[680px] text-left text-sm">{children}</table></div>
}

export function TableHead({ children }: { children: ReactNode }) {
  return <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-400"><tr>{children}</tr></thead>
}

export function TableCell({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <td className={`px-3 py-3 ${className}`}>{children}</td>
}
