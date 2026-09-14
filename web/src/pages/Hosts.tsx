import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import { HostForm, emptyHostForm, macrosFrom, type HostFormValues } from '../components/HostForm'
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
  const [adding, setAdding] = useState(false)

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
        <button type="button" className="button" onClick={() => setAdding(true)}>
          Add host
        </button>
      </div>

      {adding && <AddHostPanel onClose={() => setAdding(false)} />}

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

/**
 * Adding a host by hand.
 *
 * <p>Inline rather than a modal: the form is tall once a camera's settings
 * are showing, and a dialog that scrolls internally is worse than a panel
 * that pushes the table down.
 */
function AddHostPanel({ onClose }: { onClose: () => void }) {
  const [values, setValues] = useState<HostFormValues>(emptyHostForm)
  const queryClient = useQueryClient()

  const create = useMutation({
    mutationFn: () =>
      api.createHost({
        host: values.host.trim(),
        name: values.name.trim() || undefined,
        hostClass: values.hostClass,
        interfaces: [
          {
            // One interface carrying the address. The camera template reads
            // its RTSP and ONVIF ports from macros, so a single interface is
            // enough and a port chooser here would only be something else to
            // get wrong.
            type: 'AGENT',
            main: true,
            useIp: true,
            ip: values.address.trim(),
            port: values.hostClass === 'CAMERA' ? 80 : 10150,
          },
        ],
        macros: macrosFrom(values),
        templates: values.templates,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['hosts'] })
      onClose()
    },
  })

  const canSubmit = values.host.trim() !== '' && values.address.trim() !== ''

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-title">Add a host</div>

      <HostForm values={values} onChange={setValues} />

      {create.error && (
        <div className="error-banner">
          {create.error instanceof ApiError
            ? create.error.message
            : (create.error as Error).message}
        </div>
      )}

      <div className="row" style={{ gap: 9, marginTop: 12 }}>
        <button
          type="button"
          className="button"
          disabled={!canSubmit || create.isPending}
          onClick={() => create.mutate()}
        >
          {create.isPending ? 'Adding…' : 'Add host'}
        </button>
        <button type="button" className="button ghost" onClick={onClose}>
          Cancel
        </button>
        {!canSubmit && (
          <span className="muted" style={{ alignSelf: 'center', fontSize: 12 }}>
            A host name and an address are required.
          </span>
        )}
      </div>
    </div>
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
