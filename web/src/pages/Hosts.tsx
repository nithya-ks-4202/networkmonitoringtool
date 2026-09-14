import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Availability, HostClass } from '../api/types'

const HOST_CLASSES: { value: HostClass | 'ALL'; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'SERVER', label: 'Servers' },
  { value: 'NETWORK_DEVICE', label: 'Network' },
  { value: 'CAMERA', label: 'Cameras' },
  { value: 'APPLICATION', label: 'Applications' },
]

export function Hosts() {
  const [hostClass, setHostClass] = useState<HostClass | 'ALL'>('ALL')
  const [search, setSearch] = useState('')

  const { data, isLoading, error } = useQuery({
    queryKey: ['hosts', hostClass],
    queryFn: () => api.hosts(hostClass === 'ALL' ? undefined : hostClass),
    refetchInterval: 60_000,
  })

  const term = search.trim().toLowerCase()
  const visible = (data ?? []).filter(
    (host) =>
      term === '' ||
      host.name.toLowerCase().includes(term) ||
      host.host.toLowerCase().includes(term) ||
      host.address.toLowerCase().includes(term) ||
      host.tags.some((tag) => tag.toLowerCase().includes(term)),
  )

  return (
    <>
      <div className="page-header">
        <div>
          <h1 className="page-title">Hosts</h1>
          <div className="page-subtitle">{data ? `${visible.length} of ${data.length}` : 'Loading'}</div>
        </div>
      </div>

      <div className="filter-row">
        <div className="segmented" role="group" aria-label="Filter by host class">
          {HOST_CLASSES.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={hostClass === option.value}
              onClick={() => setHostClass(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
        <input
          className="control"
          type="search"
          placeholder="Search name, address or tag"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          style={{ minWidth: 240 }}
        />
      </div>

      {error && <div className="error-banner">{(error as Error).message}</div>}

      <div className="card" style={{ padding: 0 }}>
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th style={{ width: 150 }}>Address</th>
              <th style={{ width: 130 }}>Class</th>
              <th style={{ width: 150 }}>Availability</th>
              <th style={{ width: 90 }} className="numeric">
                Problems
              </th>
              <th style={{ width: 180 }}>Groups</th>
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr>
                <td colSpan={6} className="empty">
                  Loading hosts…
                </td>
              </tr>
            )}
            {!isLoading && visible.length === 0 && (
              <tr>
                <td colSpan={6} className="empty">
                  {data?.length === 0 ? 'No hosts are configured yet.' : 'Nothing matches that search.'}
                </td>
              </tr>
            )}
            {visible.map((host) => (
              <tr key={host.id}>
                <td className="primary">
                  <Link to={`/hosts/${host.id}`} style={{ color: 'inherit', textDecoration: 'none' }}>
                    {host.name}
                  </Link>
                  {host.maintenanceStatus === 'IN_PROGRESS' && (
                    <span className="muted" style={{ marginLeft: 8, fontSize: 11.5 }}>
                      in maintenance
                    </span>
                  )}
                  {host.proxyName && (
                    <div className="muted" style={{ fontSize: 11.5 }}>
                      via {host.proxyName}
                    </div>
                  )}
                </td>
                <td className="numeric">{host.address || '—'}</td>
                <td>{host.hostClass.replace('_', ' ').toLowerCase()}</td>
                <td>
                  <AvailabilityMark availability={host.availability} />
                </td>
                <td className="numeric">{host.openProblems || '—'}</td>
                <td className="muted">{host.groups.join(', ') || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

const AVAILABILITY_STYLE: Record<Availability, { label: string; color: string; glyph: string }> = {
  AVAILABLE: { label: 'Available', color: 'var(--status-good)', glyph: 'circle' },
  UNAVAILABLE: { label: 'Unreachable', color: 'var(--status-critical)', glyph: 'diamond' },
  UNKNOWN: { label: 'Not yet polled', color: 'var(--status-none)', glyph: 'square' },
}

function AvailabilityMark({ availability }: { availability: Availability }) {
  const style = AVAILABILITY_STYLE[availability]
  return (
    <span className="status-mark" style={{ color: style.color }}>
      <span className={`status-glyph ${style.glyph}`} style={{ background: 'currentColor' }} aria-hidden />
      <span className="severity-text">{style.label}</span>
    </span>
  )
}
