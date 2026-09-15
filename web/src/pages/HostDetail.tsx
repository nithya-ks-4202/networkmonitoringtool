import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import { HostForm, hostFormFrom, macrosFrom, type HostFormValues } from '../components/HostForm'
import type { HostDetail as HostDetailDto, LatestValue, TemplateSummary } from '../api/types'
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
  const [editing, setEditing] = useState(false)

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
            {host?.status === 'DISABLED' && ' · disabled, not being polled'}
          </div>
        </div>
        <div className="row" style={{ gap: 9 }}>
          <button type="button" className="button ghost" onClick={() => setEditing((was) => !was)}>
            {editing ? 'Close' : 'Edit'}
          </button>
          <DeleteHostButton hostId={id} name={host?.name ?? `host ${id}`} />
        </div>
      </div>

      {editing && <EditHostPanel hostId={id} onDone={() => setEditing(false)} />}

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
                  This host has no items. Link a template to it below.
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

      <TemplatePanel hostId={id} />
    </>
  )
}

/**
 * Editing a host.
 *
 * <p>The update endpoint replaces the host rather than patching it, so
 * everything it holds is read back and sent again: leaving out the
 * description empties it, leaving out the status re-enables a host somebody
 * disabled on purpose, and leaving out the proxy id moves a remote site's
 * host back to being polled from the centre. Groups and tags are the
 * exception -- the summary formats tags for reading rather than for sending,
 * so they are omitted, which the server treats as "leave them alone".
 */
function EditHostPanel({ hostId, onDone }: { hostId: number; onDone: () => void }) {
  const queryClient = useQueryClient()
  const detail = useQuery({
    queryKey: ['host', hostId],
    queryFn: () => api.host(hostId),
    enabled: Number.isFinite(hostId),
  })

  const [values, setValues] = useState<HostFormValues | null>(null)

  useEffect(() => {
    if (detail.data && values === null) {
      setValues(hostFormFrom(detail.data))
    }
  }, [detail.data, values])

  const save = useMutation({
    mutationFn: () => api.updateHost(hostId, requestFrom(detail.data!, values!)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['host', hostId] })
      queryClient.invalidateQueries({ queryKey: ['hosts'] })
      queryClient.invalidateQueries({ queryKey: ['latest', hostId] })
      queryClient.invalidateQueries({ queryKey: ['cameras'] })
      onDone()
    },
  })

  if (!detail.data || values === null) {
    return null
  }

  const canSubmit = values.host.trim() !== '' && values.address.trim() !== ''

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-title">Edit host</div>

      <HostForm values={values} onChange={setValues} showTemplates={false} mode="edit" />

      {save.error && (
        <div className="error-banner">
          {save.error instanceof ApiError ? save.error.message : (save.error as Error).message}
        </div>
      )}

      <div className="row" style={{ gap: 9, marginTop: 12 }}>
        <button
          type="button"
          className="button"
          disabled={!canSubmit || save.isPending}
          onClick={() => save.mutate()}
        >
          {save.isPending ? 'Saving…' : 'Save changes'}
        </button>
        <button type="button" className="button ghost" onClick={onDone}>
          Cancel
        </button>
        {!canSubmit && (
          <span className="muted" style={{ alignSelf: 'center', fontSize: 12 }}>
            A host name and an address are required.
          </span>
        )}
        <span className="muted" style={{ alignSelf: 'center', fontSize: 12 }}>
          Templates are changed in the Templates panel below.
        </span>
      </div>
    </div>
  )
}

/**
 * Builds the replacement from the stored host and the edited form.
 *
 * <p>The address goes to whichever field the interface actually connects on:
 * writing an IP into `ip` on an interface set to use DNS changes nothing the
 * poller reads, so the edit would appear to save and do nothing.
 */
function requestFrom(detail: HostDetailDto, values: HostFormValues) {
  const main = detail.interfaces.findIndex((candidate) => candidate.main)
  const primary = main >= 0 ? main : 0
  const address = values.address.trim()

  const interfaces =
    detail.interfaces.length > 0
      ? detail.interfaces.map((candidate, index) => ({
          id: candidate.id,
          type: candidate.type,
          main: candidate.main,
          useIp: candidate.useIp,
          ip: index === primary && candidate.useIp ? address : (candidate.ip ?? undefined),
          dns: index === primary && !candidate.useIp ? address : (candidate.dns ?? undefined),
          port: candidate.port,
        }))
      : [
          {
            type: 'AGENT',
            main: true,
            useIp: true,
            ip: address,
            port: values.hostClass === 'CAMERA' ? 80 : 10150,
          },
        ]

  return {
    host: values.host.trim(),
    name: values.name.trim() || undefined,
    hostClass: values.hostClass,
    description: detail.description,
    status: detail.summary.status,
    proxyId: detail.proxyId,
    interfaces,
    macros: macrosFrom(values),
  }
}

/**
 * Deleting a host.
 *
 * <p>Two presses rather than a dialog, and the second one says what goes with
 * it. The history is the part that cannot be recovered: a host can be added
 * again in a minute, but the months of values that made its graphs worth
 * looking at cannot.
 */
function DeleteHostButton({ hostId, name }: { hostId: number; name: string }) {
  const [armed, setArmed] = useState(false)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const remove = useMutation({
    mutationFn: () => api.deleteHost(hostId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['hosts'] })
      queryClient.invalidateQueries({ queryKey: ['cameras'] })
      queryClient.invalidateQueries({ queryKey: ['problems'] })
      navigate('/hosts')
    },
  })

  if (!armed) {
    return (
      <button type="button" className="button ghost" onClick={() => setArmed(true)}>
        Delete
      </button>
    )
  }

  return (
    <div className="row" style={{ gap: 9, alignItems: 'center' }}>
      <span className="muted" style={{ fontSize: 12, maxWidth: 320, textAlign: 'right' }}>
        {remove.error
          ? remove.error instanceof ApiError
            ? remove.error.message
            : (remove.error as Error).message
          : `Delete ${name} and every value it has collected?`}
      </span>
      <button
        type="button"
        className="button"
        disabled={remove.isPending}
        onClick={() => remove.mutate()}
      >
        {remove.isPending ? 'Deleting…' : 'Delete for good'}
      </button>
      <button type="button" className="button ghost" onClick={() => setArmed(false)}>
        Cancel
      </button>
    </div>
  )
}

/**
 * What is monitored on this host.
 *
 * <p>A host with no template collects nothing, which is the most common
 * reason a device sits on the Hosts page while the camera wall and the
 * dashboard both show nothing at all. Two screens used to advise linking a
 * template with no way to do it anywhere in the interface.
 */
function TemplatePanel({ hostId }: { hostId: number }) {
  const queryClient = useQueryClient()

  const detail = useQuery({
    queryKey: ['host', hostId],
    queryFn: () => api.host(hostId),
    enabled: Number.isFinite(hostId),
  })
  const { data: templates } = useQuery({
    queryKey: ['templates'],
    queryFn: () => api.templates(),
    staleTime: 10 * 60_000,
  })

  const [selected, setSelected] = useState<string[] | null>(null)

  // Seeded from the server once it answers, then left alone: re-seeding on
  // every refetch would discard a selection the operator is part-way through
  // making.
  useEffect(() => {
    if (detail.data && selected === null) {
      setSelected(detail.data.templates)
    }
  }, [detail.data, selected])

  const save = useMutation({
    mutationFn: () => api.setHostTemplates(hostId, selected ?? []),
    onSuccess: (updated) => {
      setSelected(updated.templates)
      queryClient.setQueryData(['host', hostId], updated)
      // The items, the wall and the host lists all change shape when a
      // template is linked, and none of them is derived from this response.
      queryClient.invalidateQueries({ queryKey: ['latest', hostId] })
      queryClient.invalidateQueries({ queryKey: ['hosts'] })
      queryClient.invalidateQueries({ queryKey: ['cameras'] })
    },
  })

  if (!detail.data || selected === null) {
    return null
  }

  const linked = detail.data.templates
  const removing = linked.filter((name) => !selected.includes(name))
  const adding = selected.filter((name) => !linked.includes(name))
  const changed = removing.length > 0 || adding.length > 0

  const toggle = (name: string) =>
    setSelected(
      selected.includes(name) ? selected.filter((n) => n !== name) : [...selected, name],
    )

  return (
    <div className="card" style={{ marginTop: 16 }}>
      <h2 className="card-title">Templates</h2>
      <div className="muted" style={{ fontSize: 12.5, marginBottom: 10 }}>
        {detail.data.itemCount} item{detail.data.itemCount === 1 ? '' : 's'} and{' '}
        {detail.data.triggerCount} trigger{detail.data.triggerCount === 1 ? '' : 's'} on this host.
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {(templates ?? []).map((template: TemplateSummary) => (
          <label
            key={template.id}
            style={{ display: 'flex', gap: 9, alignItems: 'flex-start', cursor: 'pointer' }}
          >
            <input
              type="checkbox"
              checked={selected.includes(template.name)}
              onChange={() => toggle(template.name)}
              style={{ marginTop: 3 }}
            />
            <span>
              {template.name}
              <span className="muted" style={{ marginLeft: 7, fontSize: 12 }}>
                {template.itemCount} items, {template.triggerCount} triggers
              </span>
              <div className="muted" style={{ fontSize: 12 }}>
                {template.description}
              </div>
            </span>
          </label>
        ))}
      </div>

      {/* Said before the button is pressed, not after. Unlinking deletes the
          collected history along with the items, and there is no undo for
          that anywhere in the system. */}
      {removing.length > 0 && (
        <div className="error-banner" style={{ marginTop: 12 }}>
          Unlinking {removing.join(', ')} deletes the items it created on this host, together with
          their collected history. This cannot be undone.
        </div>
      )}

      {save.error && (
        <div className="error-banner" style={{ marginTop: 12 }}>
          {save.error instanceof ApiError ? save.error.message : (save.error as Error).message}
        </div>
      )}

      <div className="row" style={{ gap: 9, marginTop: 12 }}>
        <button
          type="button"
          className="button"
          disabled={!changed || save.isPending}
          onClick={() => save.mutate()}
        >
          {save.isPending ? 'Applying…' : 'Apply'}
        </button>
        {changed && (
          <button
            type="button"
            className="button ghost"
            disabled={save.isPending}
            onClick={() => setSelected(linked)}
          >
            Reset
          </button>
        )}
        <span className="muted" style={{ alignSelf: 'center', fontSize: 12 }}>
          {changed
            ? [
                adding.length > 0 ? `linking ${adding.join(', ')}` : null,
                removing.length > 0 ? `unlinking ${removing.join(', ')}` : null,
              ]
                .filter(Boolean)
                .join(' · ')
            : linked.length === 0
              ? 'Nothing is linked, so nothing is being collected.'
              : `Linked: ${linked.join(', ')}`}
        </span>
      </div>
    </div>
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
