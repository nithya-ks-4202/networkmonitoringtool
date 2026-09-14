import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Problem, Severity } from '../api/types'
import { SEVERITY_ORDER, SeverityBadge } from '../components/SeverityBadge'

/**
 * The page operators open first and leave open.
 *
 * Sorted by severity then age, because the question is always "what is worst,
 * and how long has it been that way".
 */
export function Problems() {
  const [minSeverity, setMinSeverity] = useState<Severity>('NOT_CLASSIFIED')
  const queryClient = useQueryClient()

  const { data, isLoading, error } = useQuery({
    queryKey: ['problems', minSeverity],
    queryFn: () => api.problems(minSeverity),
    // Left open on a wall display, so it refreshes itself rather than relying
    // on someone remembering to reload.
    refetchInterval: 30_000,
  })

  const acknowledge = useMutation({
    mutationFn: (problemId: number) =>
      api.acknowledgeProblem(problemId, { acknowledge: true, message: 'Acknowledged from the problem list' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['problems'] })
      queryClient.invalidateQueries({ queryKey: ['problem-summary'] })
    },
  })

  return (
    <>
      <div className="page-header">
        <div>
          <h1 className="page-title">Problems</h1>
          <div className="page-subtitle">
            {data ? `${data.length} open` : 'Loading'} · refreshes every 30 seconds
          </div>
        </div>
      </div>

      {/* Filters in one row above the content they filter. */}
      <div className="filter-row">
        <span className="muted" style={{ fontSize: 12.5 }}>
          Minimum severity
        </span>
        <div className="segmented" role="group" aria-label="Minimum severity">
          <button
            type="button"
            aria-pressed={minSeverity === 'NOT_CLASSIFIED'}
            onClick={() => setMinSeverity('NOT_CLASSIFIED')}
          >
            All
          </button>
          {(['WARNING', 'AVERAGE', 'HIGH', 'DISASTER'] as Severity[]).map((severity) => (
            <button
              key={severity}
              type="button"
              aria-pressed={minSeverity === severity}
              onClick={() => setMinSeverity(severity)}
            >
              {severity.charAt(0) + severity.slice(1).toLowerCase()}+
            </button>
          ))}
        </div>
      </div>

      {error && <div className="error-banner">{(error as Error).message}</div>}

      <div className="card" style={{ padding: 0 }}>
        <table className="table">
          <thead>
            <tr>
              <th style={{ width: 140 }}>Severity</th>
              <th style={{ width: 90 }}>Duration</th>
              <th>Problem</th>
              <th style={{ width: 190 }}>Host</th>
              <th style={{ width: 110 }}>Status</th>
              <th style={{ width: 130 }} />
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr>
                <td colSpan={6} className="empty">
                  Loading problems…
                </td>
              </tr>
            )}

            {data?.length === 0 && (
              <tr>
                <td colSpan={6} className="empty">
                  Nothing is currently wrong.
                </td>
              </tr>
            )}

            {data?.map((problem) => (
              <ProblemRow
                key={problem.id}
                problem={problem}
                onAcknowledge={() => acknowledge.mutate(problem.id)}
                acknowledging={acknowledge.isPending && acknowledge.variables === problem.id}
              />
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

function ProblemRow({
  problem,
  onAcknowledge,
  acknowledging,
}: {
  problem: Problem
  onAcknowledge: () => void
  acknowledging: boolean
}) {
  return (
    <tr>
      <td>
        <SeverityBadge severity={problem.severity} />
      </td>
      <td className="numeric">{problem.duration}</td>
      <td className="primary">
        {problem.name}
        {problem.opdata && <div className="muted" style={{ fontSize: 12 }}>{problem.opdata}</div>}
        {problem.tags.length > 0 && (
          <div className="muted" style={{ fontSize: 11.5, marginTop: 2 }}>
            {problem.tags.slice(0, 4).join(' · ')}
          </div>
        )}
      </td>
      <td>{problem.hostName || '—'}</td>
      <td>
        {problem.suppressed ? (
          // Suppressed is shown, not hidden: the problem is real and still
          // open, it just is not paging anyone right now.
          <span className="muted">Suppressed</span>
        ) : problem.acknowledged ? (
          <span className="acknowledged">Acknowledged</span>
        ) : (
          <span className="muted">Unhandled</span>
        )}
      </td>
      <td>
        {!problem.acknowledged && (
          <button className="button ghost" onClick={onAcknowledge} disabled={acknowledging}>
            {acknowledging ? 'Working…' : 'Acknowledge'}
          </button>
        )}
      </td>
    </tr>
  )
}

/** Counts by severity, used by the dashboard and the navigation. */
export function useProblemSummary() {
  return useQuery({
    queryKey: ['problem-summary'],
    queryFn: api.problemSummary,
    refetchInterval: 30_000,
  })
}

export { SEVERITY_ORDER }
