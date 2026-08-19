import type { ReactNode } from 'react'

export function Button({ children, variant = 'primary', ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' | 'ghost' }) {
  const styles = {
    primary: 'bg-violet-700 text-[#fff8ea] hover:bg-violet-800 shadow-[0_12px_30px_rgba(91,33,182,0.24)]',
    secondary: 'bg-[#2a005c] text-[#fff8ea] hover:bg-[#3a007a]',
    danger: 'bg-red-50 text-red-700 hover:bg-red-100',
    ghost: 'text-[#4b2a7a] hover:bg-[#f5ebff] hover:text-[#220046]',
  }
  return <button {...props} className={`rounded-lg px-3 py-2 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-50 ${styles[variant]} ${props.className ?? ''}`}>{children}</button>
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
  return <section className="rounded-2xl border border-[#eadcff] bg-white/90 shadow-[0_20px_50px_rgba(60,0,112,0.08)] backdrop-blur"><div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#f1e8ff] p-4 sm:p-5"><div className="min-w-0"><h2 className="font-semibold text-[#240042]">{title}</h2>{description && <p className="mt-1 text-sm text-slate-500">{description}</p>}</div>{action && <div className="w-full sm:w-auto">{action}</div>}</div><div className="p-4 sm:p-5">{children}</div></section>
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

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' }) {
  const styles = { neutral: 'bg-[#efe5ff] text-[#4b2a7a]', success: 'bg-emerald-100 text-emerald-700', warning: 'bg-amber-100 text-amber-700', danger: 'bg-red-100 text-red-700' }
  return <span className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ${styles[tone]}`}>{children}</span>
}

export function Table({ children }: { children: ReactNode }) {
  return <div className="overflow-x-auto"><table className="w-full min-w-[680px] text-left text-sm">{children}</table></div>
}

export function TableHead({ children }: { children: ReactNode }) {
  return <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-400"><tr>{children}</tr></thead>
}

export function TableCell({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <td className={`px-3 py-3 ${className}`}>{children}</td>
}
