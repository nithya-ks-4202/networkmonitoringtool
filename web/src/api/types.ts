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
export type CameraState = 'ONLINE' | 'OFFLINE' | 'UNKNOWN'

export interface CameraStatus {
  hostId: number
  hostName: string
  technicalName: string
  /** UNKNOWN is distinct from OFFLINE: "we cannot tell" is not "it is down". */
  status: CameraState
  lastSeen: string | null | undefined
  age: string
  error: string
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
