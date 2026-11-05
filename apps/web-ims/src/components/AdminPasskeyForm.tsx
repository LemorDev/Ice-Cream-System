import { useState } from 'react'
import {
  startAuthentication, startRegistration,
  type PublicKeyCredentialCreationOptionsJSON, type PublicKeyCredentialRequestOptionsJSON,
} from '@simplewebauthn/browser'
import { FunctionsHttpError } from '@supabase/supabase-js'
import { createSupabaseClient } from '../lib/supabase'
import { Button, Input, Notice } from './ui'

export function AdminPasskeyForm({ token, onVerified, onCancel }: {
  token: string
  onVerified: (verifiedAt: string) => void
  onCancel: () => void
}) {
  const [enrolling, setEnrolling] = useState(false)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')

  async function verify() {
    setBusy(true)
    setMessage('')
    try {
      const client = createSupabaseClient(token)
      const begin = await client.functions.invoke<{
        challengeId: string
        options: PublicKeyCredentialCreationOptionsJSON | PublicKeyCredentialRequestOptionsJSON
      }>('admin-passkey', { body: { action: 'begin', ...(enrolling ? { code: code.trim().toLowerCase() } : {}) } })
      if (begin.error) throw begin.error
      const { challengeId, options } = begin.data!
      const response = enrolling
        ? await startRegistration({ optionsJSON: options as PublicKeyCredentialCreationOptionsJSON })
        : await startAuthentication({ optionsJSON: options as PublicKeyCredentialRequestOptionsJSON })
      const result = await client.functions.invoke<{ verifiedAt: string }>('admin-passkey', {
        body: { action: enrolling ? 'enroll' : 'verify', challengeId, response },
      })
      if (result.error) throw result.error
      if (!result.data?.verifiedAt) throw new Error('Verification did not complete.')
      setCode('')
      onVerified(result.data.verifiedAt)
    } catch (error) {
      const body = error instanceof FunctionsHttpError ? await error.context.json().catch(() => null) : null
      setMessage(typeof body?.error === 'string' ? body.error : 'Passkey verification did not complete. Try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void verify() }}>
      <p className="text-sm leading-6 text-slate-600">
        {enrolling ? 'Enter the one-time code from your database administrator, then follow your device’s passkey prompt.'
          : 'Use your passkey to verify this administrator session.'}
      </p>
      {enrolling && <Input label="Enrollment code" type="password" autoComplete="off" required value={code}
        onChange={(event) => setCode(event.target.value)} />}
      {message && <Notice>{message}</Notice>}
      <div className="flex flex-wrap gap-2">
        <Button className="min-h-11" disabled={busy}>{busy ? 'Waiting for passkey…' : enrolling ? 'Enroll passkey' : 'Verify passkey'}</Button>
        <Button className="min-h-11" type="button" variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
      </div>
      <Button className="min-h-11" type="button" variant="ghost" disabled={busy}
        onClick={() => { setEnrolling(!enrolling); setCode(''); setMessage('') }}>
        {enrolling ? 'Use an enrolled passkey' : 'Use an enrollment code'}
      </Button>
    </form>
  )
}
