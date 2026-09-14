import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Severity } from '../api/types'
import { SEVERITY_ORDER, SeverityBadge, severityColor } from '../components/SeverityBadge'
import { useProblemSummary } from './Problems'

export function Dashboard() {
  const summary = useProblemSummary()
  const hosts = useQuery({ queryKey: ['hosts'], queryFn: () => api.hosts(), refetchInterval: 60_000 })
  const cameras = useQuery({ queryKey: ['cameras'], queryFn: () => api.cameras(), refetchInterval: 30_000 })
  const problems = useQuery({
    queryKey: ['problems', 'NOT_CLASSIFIED'],
    queryFn: () => api.problems('NOT_CLASSIFIED', 12),
    refetchInterval: 30_000,
  })

  const counts = summary.data
  const totalProblems = counts ? Object.values(counts).reduce((sum, value) => sum + value, 0) : 0
  const unavailableHosts = hosts.data?.filter((host) => host.availability === 'UNAVAILABLE').length ?? 0
  const offlineCameras = cameras.data?.filter((camera) => camera.status === 'OFFLINE').length ?? 0

  return (
    <>
      <div className="page-header">
        <div>
          <h1 className="page-title">Overview</h1>
          <div className="page-subtitle">Current state across everything being monitored</div>
        </div>
      </div>

      {/* Headline numbers are stat tiles, not charts. A count has no shape
          worth plotting, and a donut of six severities would take more space
          to say less than six labelled numbers. */}
      <div className="grid grid-tiles" style={{ marginBottom: 16 }}>
        <StatTile label="Open problems" value={totalProblems} />
        <StatTile label="Hosts monitored" value={hosts.data?.length ?? 0} />
        <StatTile label="Hosts unreachable" value={unavailableHosts} accent="var(--status-critical)" />
        <StatTile label="Cameras offline" value={offlineCameras} accent="var(--status-critical)" />
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(280px, 1fr) 2fr', alignItems: 'start' }}>
        <div className="card">
          <h2 className="card-title">Problems by severity</h2>
          {counts && totalProblems === 0 && <div className="empty">Nothing is currently wrong.</div>}
          {counts && totalProblems > 0 && <SeverityBreakdown counts={counts} total={totalProblems} />}
        </div>

        <div className="card" style={{ padding: 0 }}>
          <h2 className="card-title" style={{ padding: '16px 16px 0' }}>
            Most recent problems
          </h2>
          <table className="table">
            <thead>
              <tr>
                <th style={{ width: 140 }}>Severity</th>
                <th style={{ width: 80 }}>Age</th>
                <th>Problem</th>
                <th style={{ width: 170 }}>Host</th>
              </tr>
            </thead>
            <tbody>
              {problems.data?.length === 0 && (
                <tr>
                  <td colSpan={4} className="empty">
                    Nothing is currently wrong.
                  </td>
                </tr>
              )}
              {problems.data?.map((problem) => (
                <tr key={problem.id}>
                  <td>
                    <SeverityBadge severity={problem.severity} />
                  </td>
                  <td className="numeric">{problem.duration}</td>
                  <td className="primary">{problem.name}</td>
                  <td>
                    {problem.hostId ? (
                      <Link to={`/hosts/${problem.hostId}`} style={{ color: 'var(--series-1)' }}>
                        {problem.hostName}
                      </Link>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

function StatTile({ label, value, accent }: { label: string; value: number; accent?: string }) {
  return (
    <div className="stat-tile">
      {/* The number takes the accent only when it is non-zero: colouring a
          zero red says something is wrong when nothing is. */}
      <div className="stat-value" style={accent && value > 0 ? { color: accent } : undefined}>
        {value}
      </div>
      <div className="stat-label">{label}</div>
    </div>
  )
}

/**
 * Severity distribution as labelled bars.
 *
 * A horizontal bar per severity rather than a pie: the counts are compared
 * against each other and against zero, and a bar does both. Every row is
 * labelled with its name and count, so the colour is reinforcement rather than
 * the sole carrier.
 */
function SeverityBreakdown({ counts, total }: { counts: Record<Severity, number>; total: number }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {SEVERITY_ORDER.filter((severity) => counts[severity] > 0).map((severity) => {
        const count = counts[severity]
        const share = total === 0 ? 0 : (count / total) * 100
        return (
          <div key={severity}>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: 4,
              }}
            >
              <SeverityBadge severity={severity} />
              <span className="numeric" style={{ fontSize: 13, fontWeight: 600 }}>
                {count}
              </span>
            </div>
            <div
              style={{ height: 6, background: 'var(--gridline)', borderRadius: 3, overflow: 'hidden' }}
              role="img"
              aria-label={`${count} of ${total} problems`}
            >
              <div
                style={{
                  width: `${share}%`,
                  height: '100%',
                  // Rounded on the data end only, anchored to the baseline.
                  borderRadius: '0 3px 3px 0',
                  background: severityColor(severity),
                }}
              />
            </div>
          </div>
        )
      })}
    </div>
  )
}
