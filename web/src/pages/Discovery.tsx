import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import { HostForm, macrosFrom, type HostFormValues } from '../components/HostForm'
import type { DiscoveredDevice, DiscoveryRule } from '../api/types'

/**
 * Finding devices on the network, and choosing which to monitor.
 *
 * <p>The page is deliberately two halves in one place: the rules that sweep,
 * and what they found. Splitting them across two pages means the answer to
 * "why has it found nothing" is always on the screen you are not looking at.
 */
export function Discovery() {
  const [creatingRule, setCreatingRule] = useState(false)
  const [includeMonitored, setIncludeMonitored] = useState(false)

  const rules = useQuery({
    queryKey: ['discovery-rules'],
    queryFn: () => api.discoveryRules(),
    refetchInterval: 15_000,
  })

  const devices = useQuery({
    queryKey: ['discovered-devices', includeMonitored],
    queryFn: () => api.discoveredDevices(includeMonitored),
    refetchInterval: 15_000,
  })

  return (
    <>
      <div className="page-header">
        <div>
          <h1 className="page-title">Discovery</h1>
          <div className="page-subtitle">
            {devices.data
              ? `${devices.data.filter((d) => !d.hostId).length} device(s) found and not yet monitored`
              : 'Loading'}
          </div>
        </div>
        <button type="button" className="button" onClick={() => setCreatingRule(true)}>
          New scan
        </button>
      </div>

      {creatingRule && <NewRulePanel onClose={() => setCreatingRule(false)} />}

      <RuleList rules={rules.data} isLoading={rules.isLoading} error={rules.error} />

      <div className="filter-row" style={{ marginTop: 20 }}>
        <h2 className="card-title" style={{ margin: 0 }}>
          Found devices
        </h2>
        <label style={{ display: 'flex', gap: 7, alignItems: 'center', fontSize: 13 }}>
          <input
            type="checkbox"
            checked={includeMonitored}
            onChange={(e) => setIncludeMonitored(e.target.checked)}
          />
          Include ones already monitored
        </label>
      </div>

      <DeviceList
        devices={devices.data}
        isLoading={devices.isLoading}
        error={devices.error}
        hasRules={(rules.data ?? []).length > 0}
      />
    </>
  )
}

function RuleList({
  rules,
  isLoading,
  error,
}: {
  rules?: DiscoveryRule[]
  isLoading: boolean
  error: unknown
}) {
  const queryClient = useQueryClient()

  const runNow = useMutation({
    mutationFn: (ruleId: number) => api.runDiscoveryRule(ruleId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['discovery-rules'] }),
  })

  const remove = useMutation({
    mutationFn: (ruleId: number) => api.deleteDiscoveryRule(ruleId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['discovery-rules'] })
      queryClient.invalidateQueries({ queryKey: ['discovered-devices'] })
    },
  })

  if (error) return <div className="error-banner">{(error as Error).message}</div>

  return (
    <div className="card" style={{ padding: 0 }}>
      <table className="table">
        <thead>
          <tr>
            <th>Scan</th>
            <th style={{ width: 200 }}>Range</th>
            <th style={{ width: 100 }} className="numeric">
              Addresses
            </th>
            <th style={{ width: 210 }}>Probes</th>
            <th style={{ width: 110 }} className="numeric">
              Pending
            </th>
            <th style={{ width: 170 }}>Next run</th>
            <th style={{ width: 140 }} />
          </tr>
        </thead>
        <tbody>
          {isLoading && (
            <tr>
              <td colSpan={7} className="empty">
                Loading scans…
              </td>
            </tr>
          )}
          {!isLoading && (rules ?? []).length === 0 && (
            <tr>
              <td colSpan={7} className="empty">
                No scans yet. Create one to sweep a range of addresses for devices.
              </td>
            </tr>
          )}
          {(rules ?? []).map((rule) => (
            <tr key={rule.id}>
              <td className="primary">{rule.name}</td>
              <td className="numeric">{rule.ipRange}</td>
              <td className="numeric">{rule.addressCount}</td>
              <td className="muted" style={{ fontSize: 12 }}>
                {[
                  rule.ping ? 'ping' : null,
                  rule.tcpPorts ? `tcp ${rule.tcpPorts}` : null,
                  rule.snmp ? 'snmp' : null,
                  rule.onvif ? 'onvif' : null,
                ]
                  .filter(Boolean)
                  .join(', ')}
              </td>
              <td className="numeric">{rule.pendingDevices || '—'}</td>
              <td className="muted">{formatWhen(rule.nextRunAt)}</td>
              <td>
                <button
                  type="button"
                  className="button ghost"
                  style={{ padding: '3px 9px', fontSize: 12 }}
                  disabled={runNow.isPending}
                  onClick={() => runNow.mutate(rule.id)}
                >
                  Run now
                </button>
                <button
                  type="button"
                  className="button ghost"
                  style={{ padding: '3px 9px', fontSize: 12, marginLeft: 5 }}
                  disabled={remove.isPending}
                  onClick={() => {
                    // Deleting a scan discards everything it found, which is
                    // not obvious from a row of buttons.
                    if (
                      window.confirm(
                        `Delete "${rule.name}" and the ${rule.pendingDevices} device(s) it found? ` +
                          'Hosts already created from them are not affected.',
                      )
                    ) {
                      remove.mutate(rule.id)
                    }
                  }}
                >
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DeviceList({
  devices,
  isLoading,
  error,
  hasRules,
}: {
  devices?: DiscoveredDevice[]
  isLoading: boolean
  error: unknown
  hasRules: boolean
}) {
  const [adding, setAdding] = useState<DiscoveredDevice | null>(null)

  if (error) return <div className="error-banner">{(error as Error).message}</div>

  return (
    <>
      {adding && <PromotePanel device={adding} onClose={() => setAdding(null)} />}

      <div className="card" style={{ padding: 0 }}>
        <table className="table">
          <thead>
            <tr>
              <th style={{ width: 140 }}>Address</th>
              <th style={{ width: 130 }}>Looks like</th>
              <th>Why</th>
              <th style={{ width: 160 }}>Answered</th>
              <th style={{ width: 150 }}>Last seen</th>
              <th style={{ width: 120 }} />
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr>
                <td colSpan={6} className="empty">
                  Loading devices…
                </td>
              </tr>
            )}
            {!isLoading && (devices ?? []).length === 0 && (
              <tr>
                <td colSpan={6} className="empty">
                  {hasRules
                    ? 'Nothing found yet. A scan runs shortly after it is created; use "Run now" to start one immediately.'
                    : 'Create a scan above to look for devices.'}
                </td>
              </tr>
            )}
            {(devices ?? []).map((device) => (
              <tr key={device.id}>
                <td className="numeric primary">
                  {device.ip}
                  {device.status === 'DOWN' && (
                    <span className="muted" style={{ marginLeft: 7, fontSize: 11.5 }}>
                      not answering
                    </span>
                  )}
                </td>
                <td>{device.suggestedClass.replace('_', ' ').toLowerCase()}</td>
                <td className="muted" style={{ fontSize: 12 }}>
                  {device.reason}
                </td>
                <td className="muted" style={{ fontSize: 12 }}>
                  {summarise(device)}
                </td>
                <td className="muted">{formatWhen(device.lastSeenAt)}</td>
                <td>
                  {device.hostId ? (
                    <span className="muted" style={{ fontSize: 12 }}>
                      {device.hostName}
                    </span>
                  ) : (
                    <button
                      type="button"
                      className="button ghost"
                      style={{ padding: '3px 9px', fontSize: 12 }}
                      onClick={() => setAdding(device)}
                    >
                      Monitor
                    </button>
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

/**
 * Confirming a discovered device before it becomes a host.
 *
 * <p>Prefilled from the classifier's guess, and editable. The guess is right
 * often enough to be worth making and wrong often enough that committing it
 * unseen would produce hosts nobody can explain.
 */
function PromotePanel({ device, onClose }: { device: DiscoveredDevice; onClose: () => void }) {
  const queryClient = useQueryClient()

  const [values, setValues] = useState<HostFormValues>(() => ({
    host: device.suggestedHost,
    name: device.suggestedName ?? device.ip,
    hostClass: device.suggestedClass,
    address: device.ip,
    templates: device.suggestedTemplate ? [device.suggestedTemplate] : [],
    cameraPath: '',
    cameraUser: '',
    cameraPassword: '',
  }))

  const promote = useMutation({
    mutationFn: () =>
      api.promoteDevice(device.id, {
        host: values.host.trim(),
        name: values.name.trim() || undefined,
        hostClass: values.hostClass,
        templates: values.templates,
        macros: macrosFrom(values),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['discovered-devices'] })
      queryClient.invalidateQueries({ queryKey: ['hosts'] })
      onClose()
    },
  })

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-title">Monitor {device.ip}</div>
      <div className="muted" style={{ fontSize: 12, marginBottom: 12 }}>
        Suggested because it {device.reason}. Change anything that looks wrong before adding it.
      </div>

      {/* The address came from the sweep, so it is shown but not editable --
          editing it here would mean the host no longer matches the device
          that was found, and the link back would be a lie. */}
      <HostForm values={values} onChange={setValues} addressEditable={false} />

      {promote.error && (
        <div className="error-banner">
          {promote.error instanceof ApiError
            ? promote.error.message
            : (promote.error as Error).message}
        </div>
      )}

      <div className="row" style={{ gap: 9, marginTop: 12 }}>
        <button
          type="button"
          className="button"
          disabled={values.host.trim() === '' || promote.isPending}
          onClick={() => promote.mutate()}
        >
          {promote.isPending ? 'Adding…' : 'Add as host'}
        </button>
        <button type="button" className="button ghost" onClick={onClose}>
          Cancel
        </button>
      </div>
    </div>
  )
}

function NewRulePanel({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [ipRange, setIpRange] = useState('')
  const [tcpPorts, setTcpPorts] = useState('80,443,554,22,3389,8000,8080')
  const [ping, setPing] = useState(true)
  const [snmp, setSnmp] = useState(false)
  const [snmpCommunity, setSnmpCommunity] = useState('public')
  const [onvif, setOnvif] = useState(false)
  const [intervalMinutes, setIntervalMinutes] = useState(60)

  const create = useMutation({
    mutationFn: () =>
      api.createDiscoveryRule({
        name: name.trim(),
        ipRange: ipRange.trim(),
        delaySeconds: Math.max(1, intervalMinutes) * 60,
        ping,
        tcpPorts,
        snmp,
        snmpCommunity: snmp ? snmpCommunity : undefined,
        onvif,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['discovery-rules'] })
      onClose()
    },
  })

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-title">New scan</div>

      <div className="row" style={{ gap: 14 }}>
        <div className="field" style={{ flex: 1 }}>
          <label htmlFor="rule-name">Name</label>
          <input
            id="rule-name"
            className="control"
            value={name}
            placeholder="Camera VLAN"
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <div className="field" style={{ flex: 1 }}>
          <label htmlFor="rule-range">Address range</label>
          <input
            id="rule-range"
            className="control"
            value={ipRange}
            placeholder="10.30.5.1-254"
            onChange={(e) => setIpRange(e.target.value)}
          />
          <div className="muted" style={{ fontSize: 12 }}>
            One address, <code>10.30.5.1-254</code>, <code>10.30.5.0/24</code>, or several
            separated by commas.
          </div>
        </div>
      </div>

      <div className="field">
        <label htmlFor="rule-ports">TCP ports to try</label>
        <input
          id="rule-ports"
          className="control"
          value={tcpPorts}
          onChange={(e) => setTcpPorts(e.target.value)}
        />
        <div className="muted" style={{ fontSize: 12 }}>
          These identify a device rather than enumerate it — 554 is what marks a camera. Keep the
          list short: every extra port is another connection to every address in the range.
        </div>
      </div>

      <div className="row" style={{ gap: 18, flexWrap: 'wrap' }}>
        <label style={{ display: 'flex', gap: 7, alignItems: 'center', fontSize: 13 }}>
          <input type="checkbox" checked={ping} onChange={(e) => setPing(e.target.checked)} />
          Ping
        </label>
        <label style={{ display: 'flex', gap: 7, alignItems: 'center', fontSize: 13 }}>
          <input type="checkbox" checked={snmp} onChange={(e) => setSnmp(e.target.checked)} />
          SNMP — names the device, and identifies switches
        </label>
        {snmp && (
          <input
            className="control"
            value={snmpCommunity}
            aria-label="SNMP community"
            style={{ width: 150 }}
            onChange={(e) => setSnmpCommunity(e.target.value)}
          />
        )}
        <label style={{ display: 'flex', gap: 7, alignItems: 'center', fontSize: 13 }}>
          <input type="checkbox" checked={onvif} onChange={(e) => setOnvif(e.target.checked)} />
          ONVIF — proves a camera is a camera
        </label>
        <label style={{ display: 'flex', gap: 7, alignItems: 'center', fontSize: 13 }}>
          Repeat every
          <input
            className="control"
            type="number"
            min={1}
            value={intervalMinutes}
            style={{ width: 80 }}
            onChange={(e) => setIntervalMinutes(Number(e.target.value))}
          />
          minutes
        </label>
      </div>

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
          disabled={name.trim() === '' || ipRange.trim() === '' || create.isPending}
          onClick={() => create.mutate()}
        >
          {create.isPending ? 'Creating…' : 'Create and run'}
        </button>
        <button type="button" className="button ghost" onClick={onClose}>
          Cancel
        </button>
      </div>
    </div>
  )
}

/** A short summary of what actually answered, for the table. */
function summarise(device: DiscoveredDevice): string {
  const parts: string[] = []
  if (device.checkResults['icmp']) parts.push('ping')
  const ports = device.checkResults['tcp.open']
  if (ports) parts.push(`tcp ${ports}`)
  if (device.checkResults['snmp.sysDescr']) parts.push('snmp')
  if (device.checkResults['onvif.device.info']) parts.push('onvif')
  return parts.join(', ') || '—'
}

function formatWhen(iso: string | null): string {
  if (!iso) return '—'
  const when = new Date(iso)
  const deltaSeconds = Math.round((when.getTime() - Date.now()) / 1000)
  const absolute = Math.abs(deltaSeconds)

  if (absolute < 60) return deltaSeconds >= 0 ? 'in under a minute' : 'just now'
  if (absolute < 3600) {
    const minutes = Math.round(absolute / 60)
    return deltaSeconds >= 0 ? `in ${minutes} min` : `${minutes} min ago`
  }
  if (absolute < 86_400) {
    const hours = Math.round(absolute / 3600)
    return deltaSeconds >= 0 ? `in ${hours} h` : `${hours} h ago`
  }
  return when.toLocaleString()
}
