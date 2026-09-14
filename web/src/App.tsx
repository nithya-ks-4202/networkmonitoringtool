import { useState } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { clearSession, loadSession } from './api/client'
import { Dashboard } from './pages/Dashboard'
import { Problems, useProblemSummary } from './pages/Problems'
import { Hosts } from './pages/Hosts'
import { HostDetail } from './pages/HostDetail'
import { CameraWall } from './pages/CameraWall'
import { Login } from './pages/Login'

export function App() {
  const [signedIn, setSignedIn] = useState(() => loadSession() !== null)

  if (!signedIn) {
    return <Login onSignedIn={() => setSignedIn(true)} />
  }

  return (
    <div className="app">
      <Sidebar onSignOut={() => { clearSession(); setSignedIn(false) }} />
      <main className="main">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/problems" element={<Problems />} />
          <Route path="/hosts" element={<Hosts />} />
          <Route path="/hosts/:hostId" element={<HostDetail />} />
          <Route path="/cameras" element={<CameraWall />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  )
}

function Sidebar({ onSignOut }: { onSignOut: () => void }) {
  const summary = useProblemSummary()
  const session = loadSession()

  // Only the severities that actually page someone are counted in the badge.
  // Including informational problems would make the number permanently large
  // and therefore permanently ignored.
  const urgent = summary.data
    ? summary.data.DISASTER + summary.data.HIGH + summary.data.AVERAGE
    : 0

  return (
    <nav className="sidebar">
      <div className="sidebar-brand">Network Monitoring</div>

      <NavItem to="/" label="Overview" />
      <NavItem to="/problems" label="Problems" count={urgent} />
      <NavItem to="/hosts" label="Hosts" />
      <NavItem to="/cameras" label="Cameras" />

      <div style={{ marginTop: 'auto', paddingTop: 16 }}>
        <div className="muted" style={{ fontSize: 12, padding: '0 10px 8px' }}>
          {session?.username}
        </div>
        <button className="button ghost" onClick={onSignOut} style={{ width: '100%' }}>
          Sign out
        </button>
      </div>
    </nav>
  )
}

function NavItem({ to, label, count }: { to: string; label: string; count?: number }) {
  return (
    <NavLink to={to} end={to === '/'} className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
      <span>{label}</span>
      {count !== undefined && count > 0 && <span className="nav-count">{count}</span>}
    </NavLink>
  )
}
