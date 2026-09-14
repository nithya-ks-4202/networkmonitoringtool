import { useState, type FormEvent } from 'react'
import { api } from '../api/client'

export function Login({ onSignedIn }: { onSignedIn: () => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.login(username, password)
      onSignedIn()
    } catch (cause) {
      // The server's message is shown verbatim: it distinguishes a locked
      // account from wrong credentials, which is the difference between
      // "try again" and "call an administrator".
      setError(cause instanceof Error ? cause.message : 'Could not sign in')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-shell">
      <form className="login-card" onSubmit={submit}>
        <h1>Network Monitoring</h1>
        <p className="page-subtitle" style={{ marginTop: 0, marginBottom: 18 }}>
          Sign in to continue
        </p>

        {error && <div className="error-banner">{error}</div>}

        <div className="field">
          <label htmlFor="username">Username</label>
          <input
            id="username"
            className="control"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="username"
            autoFocus
            required
          />
        </div>

        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            className="control"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            required
          />
        </div>

        <button className="button" type="submit" disabled={busy} style={{ width: '100%', marginTop: 6 }}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
