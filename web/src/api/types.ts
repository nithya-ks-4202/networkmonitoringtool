/** Shapes returned by the monitoring API. */

export type Severity =
  | 'NOT_CLASSIFIED'
  | 'INFORMATION'
  | 'WARNING'
  | 'AVERAGE'
  | 'HIGH'
  | 'DISASTER'

export type HostClass =
  | 'GENERIC'
  | 'SERVER'
  | 'NETWORK_DEVICE'
  | 'CAMERA'
  | 'PRINTER'
  | 'UPS'
  | 'STORAGE'
  | 'HYPERVISOR'
  | 'APPLICATION'
  | 'CLOUD'

export type Availability = 'UNKNOWN' | 'AVAILABLE' | 'UNAVAILABLE'

export type ItemState = 'NORMAL' | 'NOT_SUPPORTED'

export interface Session {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  userId: number
  username: string
  fullName: string
  role: string | null
  tenantId: number
  permissions: string[]
  theme: string
  timezone: string
}

export interface Problem {
  id: number
  name: string
  severity: Severity
  originalSeverity: Severity
  startedAt: string
  duration: string
  acknowledged: boolean
  suppressed: boolean
  resolved: boolean
  resolvedAt: string | null | undefined
  hostId: number | null
  hostName: string
  opdata: string
  tags: string[]
  updateCount: number
}

export interface HostSummary {
  id: number
  host: string
  name: string
  hostClass: HostClass
  status: 'ENABLED' | 'DISABLED'
  maintenanceStatus: 'NONE' | 'IN_PROGRESS'
  address: string
  groups: string[]
  tags: string[]
  proxyName: string | null
  availability: Availability
  openProblems: number
  updatedAt: string
}

/** A host with everything the detail view shows. */
export interface HostDetail {
  summary: HostSummary
  description: string
  interfaces: {
    id: number
    type: string
    main: boolean
    useIp: boolean
    ip: string
    dns: string
    port: number
    available: Availability
    error: string
  }[]
  macros: Record<string, string>
  inventory: Record<string, string>
  /** Template display names, the same strings the templates endpoint returns. */
  templates: string[]
  proxyId: number | null
  itemCount: number
  triggerCount: number
}

export interface LatestValue {
  itemId: number
  name: string
  key: string
  units: string
  valueType: string
  state: ItemState
  error: string
  clock: string | null | undefined
  value: string
  numericValue: number | null
  age: string
}

/** What the camera wall shows for one device. */
/**
 * What the wall shows for one camera.
 *
 * IMPAIRED is reachable-but-faulty: a dead SD card or a hung encoder still
 * answers ping, so it must not read as ONLINE -- and it is not OFFLINE either,
 * because the camera is there and streaming.
 */
export type CameraState = 'ONLINE' | 'IMPAIRED' | 'OFFLINE' | 'UNKNOWN'

export interface CameraStatus {
  hostId: number
  hostName: string
  technicalName: string
  /** UNKNOWN is distinct from OFFLINE: "we cannot tell" is not "it is down". */
  status: CameraState
  lastSeen: string | null | undefined
  age: string
  error: string
  /** The most urgent open problem, or null when there is none. */
  problem: string | null
  problemSeverity: Severity | null
  problemCount: number
}

/**
 * One bucket of a graph series.
 *
 * Carries min, average and max rather than a single number, so a chart can
 * shade the range: a bucket averaging 40% that peaked at 100% is a very
 * different picture from one that sat flat at 40%.
 */
export interface GraphPoint {
  clock: string
  min: number
  avg: number
  max: number
}

export interface GraphSeries {
  itemId: number
  name: string
  units: string
  valueType: string
  from: string
  to: string
  points: GraphPoint[]
}

export type SeverityCounts = Record<Severity, number>

/** A template that can be linked to a host. */
export interface TemplateSummary {
  id: number
  name: string
  description: string
  hostClass: HostClass
  itemCount: number
  triggerCount: number
}

/** Creating a host. Everything but the first three fields is optional. */
export interface HostCreateRequest {
  host: string
  hostClass: HostClass
  interfaces: {
    type: 'AGENT' | 'SNMP' | 'HTTP' | 'RTSP' | 'ONVIF'
    main: boolean
    useIp: boolean
    ip?: string
    dns?: string
    port: number
    snmpCommunity?: string
  }[]
  name?: string
  description?: string
  groups?: string[]
  macros?: Record<string, string>
  templates?: string[]
}

/** A standing instruction to sweep a range of addresses. */
export interface DiscoveryRule {
  id: number
  name: string
  ipRange: string
  addressCount: number
  delaySeconds: number
  concurrency: number
  status: string
  nextRunAt: string | null
  ping: boolean
  tcpPorts: string
  snmp: boolean
  onvif: boolean
  pendingDevices: number
}

export interface DiscoveryRuleRequest {
  name: string
  ipRange: string
  delaySeconds?: number
  concurrency?: number
  ping?: boolean
  tcpPorts?: string
  snmp?: boolean
  snmpCommunity?: string
  onvif?: boolean
}

/**
 * A device a sweep found.
 *
 * <p>The `suggested*` fields are a guess, and `reason` is the evidence behind
 * it -- shown so an operator can disagree before it becomes a host.
 */
export interface DiscoveredDevice {
  id: number
  ruleId: number
  ruleName: string
  ip: string
  dns: string
  status: 'UP' | 'DOWN'
  firstSeenAt: string
  lastSeenAt: string
  checkResults: Record<string, string>
  suggestedClass: HostClass
  suggestedTemplate: string | null
  /** The technical name promoting would use. Computed by the server so the
   *  form and the API cannot disagree about it. */
  suggestedHost: string
  suggestedName: string | null
  reason: string
  hostId: number | null
  hostName: string | null
}

export interface PromoteRequest {
  host?: string
  name?: string
  hostClass?: HostClass
  templates?: string[]
  groups?: string[]
  macros?: Record<string, string>
}
