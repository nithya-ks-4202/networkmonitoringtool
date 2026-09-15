import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CameraState, CameraStatus } from '../api/types'
import { CameraStateMark, cameraStateClass, cameraStateLabel } from '../components/SeverityBadge'

/**
 * Online/offline state for every camera, as a wall display.
 *
 * Offline cameras sort first. On a screen showing three hundred devices, the
 * four that are broken must not require scrolling to find.
 */
export function CameraWall() {
  const [showOnly, setShowOnly] = useState<'ALL' | CameraState>('ALL')

  const { data, isLoading, error, dataUpdatedAt } = useQuery({
    queryKey: ['cameras'],
    queryFn: () => api.cameras('icmpping'),
    refetchInterval: 30_000,
  })

  const counts = useMemo(() => summarise(data ?? []), [data])

  const visible = useMemo(() => {
    const cameras = data ?? []
    const filtered = showOnly === 'ALL' ? cameras : cameras.filter((c) => c.status === showOnly)
    return [...filtered].sort(byUrgencyThenName)
  }, [data, showOnly])

  return (
    <>
      <div className="page-header">
        <div>
          <h1 className="page-title">Cameras</h1>
          <div className="page-subtitle">
            {data ? `${data.length} devices` : 'Loading'}
            {dataUpdatedAt > 0 && ` · updated ${new Date(dataUpdatedAt).toLocaleTimeString()}`}
          </div>
        </div>
      </div>

      {/* Stat tiles, not charts: three numbers need no axes, and a pie of three
          slices would be harder to read than the numbers themselves. */}
      <div className="grid grid-tiles" style={{ marginBottom: 16 }}>
        <StateTile state="ONLINE" count={counts.ONLINE} />
        <StateTile state="IMPAIRED" count={counts.IMPAIRED} />
        <StateTile state="OFFLINE" count={counts.OFFLINE} />
        <StateTile state="UNKNOWN" count={counts.UNKNOWN} />
      </div>

      <div className="filter-row">
        <div className="segmented" role="group" aria-label="Filter by state">
          {(['ALL', 'OFFLINE', 'IMPAIRED', 'UNKNOWN', 'ONLINE'] as const).map((state) => (
            <button
              key={state}
              type="button"
              aria-pressed={showOnly === state}
              onClick={() => setShowOnly(state)}
            >
              {state === 'ALL' ? 'All' : cameraStateLabel(state)}
            </button>
          ))}
        </div>
      </div>

      {error && <div className="error-banner">{(error as Error).message}</div>}

      {isLoading && <div className="empty">Loading cameras…</div>}

      {data?.length === 0 && <EmptyWall />}

      <div className="camera-grid">
        {visible.map((camera) => (
          <Link
            key={camera.hostId}
            to={`/hosts/${camera.hostId}`}
            className={`camera-tile ${cameraStateClass(camera.status)}`}
          >
            <div className="camera-name" title={camera.hostName}>
              {camera.hostName}
            </div>
            <CameraStateMark state={camera.status} />
            <div className="camera-meta">
              {/* The fault itself, not just that there is one: "Faulty" sends
                  nobody anywhere useful, whereas "not recording (storage
                  failed)" is the difference between dispatching an engineer
                  with a replacement card and dispatching one to look at a
                  camera that is streaming perfectly. */}
              {camera.status === 'IMPAIRED' && camera.problem
                ? camera.problem +
                  (camera.problemCount > 1 ? ` (+${camera.problemCount - 1} more)` : '')
                : camera.status === 'UNKNOWN' && camera.error
                  ? camera.error
                  : camera.lastSeen != null
                    ? `checked ${camera.age} ago`
                    : 'never checked'}
            </div>
          </Link>
        ))}
      </div>
    </>
  )
}

/**
 * Why the wall is empty.
 *
 * Two quite different situations produce an identical empty wall, because the
 * query behind it is driven by the reachability item rather than by the host:
 * there are no cameras at all, or there are cameras that nothing is checking.
 * Telling an operator to "add a host with class Camera" when they have just
 * added one -- and the real omission is a template that was never linked --
 * sends them to do the one thing that cannot help.
 */
function EmptyWall() {
  // Only mounted when the wall came back empty, so the normal case pays
  // nothing for this. The key matches the Hosts page's, so adding a host
  // invalidates both.
  const { data: cameraHosts, isLoading } = useQuery({
    queryKey: ['hosts', 'CAMERA'],
    queryFn: () => api.hosts('CAMERA'),
    staleTime: 30_000,
  })

  if (isLoading || !cameraHosts) {
    return null
  }

  if (cameraHosts.length === 0) {
    return (
      <div className="card">
        <div className="empty">
          No cameras are configured yet. Add a host with class <strong>Camera</strong> on the{' '}
          <Link to="/hosts">Hosts</Link> page, or find them with a{' '}
          <Link to="/discovery">discovery</Link> sweep.
        </div>
      </div>
    )
  }

  return (
    <div className="card">
      <div className="empty" style={{ textAlign: 'left' }}>
        <div>
          {cameraHosts.length === 1
            ? 'One camera host is configured, but nothing is checking whether it is up. Open it below'
            : `${cameraHosts.length} camera hosts are configured, but nothing is checking whether they are up. Open each below`}{' '}
          and link <strong>Template: IP camera</strong> in its Templates panel; the wall fills in on
          the next poll.
        </div>
        <ul style={{ margin: '10px 0 0', paddingLeft: 18 }}>
          {cameraHosts.slice(0, 10).map((host) => (
            <li key={host.id} style={{ marginBottom: 3 }}>
              <Link to={`/hosts/${host.id}`}>{host.name}</Link>{' '}
              <span className="muted">{host.address}</span>
            </li>
          ))}
        </ul>
        {cameraHosts.length > 10 && (
          <div className="muted" style={{ marginTop: 6 }}>
            and {cameraHosts.length - 10} more.
          </div>
        )}
      </div>
    </div>
  )
}

function StateTile({ state, count }: { state: CameraState; count: number }) {
  return (
    <div className="stat-tile">
      <div className="stat-value">{count}</div>
      <div className="stat-label">
        <CameraStateMark state={state} />
      </div>
    </div>
  )
}

function summarise(cameras: CameraStatus[]): Record<CameraState, number> {
  const counts: Record<CameraState, number> = { ONLINE: 0, IMPAIRED: 0, OFFLINE: 0, UNKNOWN: 0 }
  for (const camera of cameras) {
    counts[camera.status] += 1
  }
  return counts
}

/**
 * Offline first, then faulty, then unknown, then online; alphabetical within
 * each.
 *
 * Faulty outranks unknown because it is a confirmed fault rather than an
 * absence of information -- a camera that is definitely not recording needs
 * attention before one we merely cannot read.
 */
function byUrgencyThenName(a: CameraStatus, b: CameraStatus): number {
  const rank: Record<CameraState, number> = { OFFLINE: 0, IMPAIRED: 1, UNKNOWN: 2, ONLINE: 3 }
  const byState = rank[a.status] - rank[b.status]
  return byState !== 0 ? byState : a.hostName.localeCompare(b.hostName)
}
