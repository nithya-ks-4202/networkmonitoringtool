import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { LatestValue } from '../api/types'
import { MetricChart, formatValue } from '../components/MetricChart'

const RANGES = [
  { label: '1h', hours: 1 },
  { label: '6h', hours: 6 },
  { label: '24h', hours: 24 },
  { label: '7d', hours: 24 * 7 },
  { label: '30d', hours: 24 * 30 },
]

export function HostDetail() {
  const { hostId } = useParams<{ hostId: string }>()
  const id = Number(hostId)

  const [selectedItemId, setSelectedItemId] = useState<number | null>(null)
  const [rangeHours, setRangeHours] = useState(6)

  const hosts = useQuery({ queryKey: ['hosts'], queryFn: () => api.hosts() })
  const host = hosts.data?.find((candidate) => candidate.id === id)

  const latest = useQuery({
    queryKey: ['latest', id],
    queryFn: () => api.latestValues(id),
    refetchInterval: 30_000,
    enabled: Number.isFinite(id),
  })

  // Charts the first numeric item that actually has a value, not merely the
  // first alphabetically. A host's items are mostly named before they are
  // collected, so picking blindly lands on an empty graph while a populated one
  // sits two rows below it.
  const numericItems = (latest.data ?? []).filter(isChartable)
  // `!= null` rather than `!== null`: a JSON field can arrive as null or be
  // absent altogether, and only the loose form catches both.
  const defaultItem = numericItems.find((item) => item.clock != null) ?? numericItems[0]
  const chartItemId = selectedItemId ?? defaultItem?.itemId ?? null

  const to = new Date()
  const from = new Date(to.getTime() - rangeHours * 3600_000)

  const series = useQuery({
    queryKey: ['history', chartItemId, rangeHours],
    queryFn: () => api.itemHistory(chartItemId!, from.toISOString(), to.toISOString()),
    enabled: chartItemId !== null,
    refetchInterval: 60_000,
  })

  return (
    <>
      <div className="page-header">
        <div>
          <h1 className="page-title">{host?.name ?? `Host ${id}`}</h1>
          <div className="page-subtitle">
            {host ? `${host.address || 'no address'} · ${host.hostClass.replace('_', ' ').toLowerCase()}` : ''}
          </div>
        </div>
      </div>

      {latest.error && <div className="error-banner">{(latest.error as Error).message}</div>}

      {chartItemId !== null && (
        <div style={{ marginBottom: 16 }}>
          <div className="filter-row">
            <select
              className="control"
              value={chartItemId}
              onChange={(event) => setSelectedItemId(Number(event.target.value))}
              aria-label="Metric to chart"
              style={{ minWidth: 260 }}
            >
              {numericItems.map((item) => (
                <option key={item.itemId} value={item.itemId}>
                  {item.name}
                </option>
              ))}
            </select>

            <div className="segmented" role="group" aria-label="Time range">
              {RANGES.map((range) => (
                <button
                  key={range.label}
                  type="button"
                  aria-pressed={rangeHours === range.hours}
                  onClick={() => setRangeHours(range.hours)}
                >
                  {range.label}
                </button>
              ))}
            </div>
          </div>

          {series.data && <MetricChart series={series.data} height={240} />}
          {series.isLoading && <div className="chart-shell"><div className="empty">Loading…</div></div>}
        </div>
      )}

      <div className="card" style={{ padding: 0 }}>
        <h2 className="card-title" style={{ padding: '16px 16px 0' }}>
          Latest data
        </h2>
        <table className="table">
          <thead>
            <tr>
              <th>Item</th>
              <th style={{ width: 150 }} className="numeric">
                Value
              </th>
              <th style={{ width: 100 }} className="numeric">
                Age
              </th>
              <th style={{ width: 240 }}>State</th>
            </tr>
          </thead>
          <tbody>
            {latest.isLoading && (
              <tr>
                <td colSpan={4} className="empty">
                  Loading…
                </td>
              </tr>
            )}
            {latest.data?.length === 0 && (
              <tr>
                <td colSpan={4} className="empty">
                  This host has no items. Link a template to it.
                </td>
              </tr>
            )}
            {latest.data?.map((item) => (
              <tr key={item.itemId}>
                <td className="primary">
                  {item.name}
                  <div className="muted" style={{ fontSize: 11.5 }}>
                    {item.key}
                  </div>
                </td>
                <td className="numeric primary">{renderValue(item)}</td>
                <td className="numeric muted">{item.clock != null ? item.age : '—'}</td>
                <td>
                  {item.state === 'NOT_SUPPORTED' ? (
                    // The reason is shown, not just the fact: a gap in a graph
                    // with no explanation is the least useful thing a
                    // monitoring system can present.
                    <span style={{ color: 'var(--status-serious)' }} title={item.error}>
                      Not collected — {truncate(item.error)}
                    </span>
                  ) : item.clock == null ? (
                    // Never polled is not the same as healthy. Reporting "OK"
                    // for an item that has produced nothing would mean a
                    // mis-scheduled item looks fine indefinitely.
                    <span className="muted">Awaiting first collection</span>
                  ) : (
                    <span className="muted">OK</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

function isChartable(item: LatestValue): boolean {
  return item.valueType === 'UNSIGNED' || item.valueType === 'FLOAT'
}

function renderValue(item: LatestValue): string {
  if (item.clock == null) return '—'
  if (item.numericValue != null) return formatValue(item.numericValue, item.units)
  return item.value || '—'
}

function truncate(text: string): string {
  return text.length <= 60 ? text : `${text.slice(0, 60)}…`
}
