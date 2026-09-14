import type {
  CameraStatus,
  DiscoveredDevice,
  DiscoveryRule,
  DiscoveryRuleRequest,
  GraphSeries,
  HostCreateRequest,
  HostSummary,
  LatestValue,
  Problem,
  PromoteRequest,
  Session,
  Severity,
  SeverityCounts,
  TemplateSummary,
} from './types'

const TOKEN_KEY = 'nms.session'

/** The API error surfaced to the interface, with the server's own message. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface StoredSession {
  accessToken: string
  refreshToken: string
  username: string
  permissions: string[]
}

export function loadSession(): StoredSession | null {
  const raw = localStorage.getItem(TOKEN_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as StoredSession
  } catch {
    // A corrupted entry would otherwise wedge the interface on every load.
    localStorage.removeItem(TOKEN_KEY)
    return null
  }
}

export function storeSession(session: Session): void {
  const stored: StoredSession = {
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    username: session.username,
    permissions: session.permissions,
  }
  localStorage.setItem(TOKEN_KEY, JSON.stringify(stored))
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * Performs an API request, refreshing the access token once if it has expired.
 *
 * The single retry matters: access tokens are short-lived by design, so a
 * dashboard left open overnight would otherwise log the operator out at exactly
 * the moment an incident wakes them up.
 */
async function request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const session = loadSession()

  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}),
      ...init.headers,
    },
  })

  if (response.status === 401 && session && retry) {
    const refreshed = await tryRefresh(session.refreshToken)
    if (refreshed) {
      return request<T>(path, init, false)
    }
    clearSession()
    throw new ApiError(401, 'Your session has expired. Sign in again.')
  }

  if (!response.ok) {
    throw new ApiError(response.status, await readErrorMessage(response))
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body = await response.json()
    // The server writes these for the person reading them, so prefer its
    // message over any generic text invented here.
    return body.message ?? body.error ?? `Request failed with status ${response.status}`
  } catch {
    return `Request failed with status ${response.status}`
  }
}

async function tryRefresh(refreshToken: string): Promise<boolean> {
  try {
    const response = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    if (!response.ok) return false
    storeSession((await response.json()) as Session)
    return true
  } catch {
    return false
  }
}

export const api = {
  async login(username: string, password: string): Promise<Session> {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })
    if (!response.ok) {
      throw new ApiError(response.status, await readErrorMessage(response))
    }
    const session = (await response.json()) as Session
    storeSession(session)
    return session
  },

  problems(minSeverity: Severity = 'NOT_CLASSIFIED', limit = 200): Promise<Problem[]> {
    return request(`/api/problems?minSeverity=${minSeverity}&limit=${limit}`)
  },

  problemSummary(): Promise<SeverityCounts> {
    return request('/api/problems/summary')
  },

  acknowledgeProblem(
    problemId: number,
    body: {
      acknowledge?: boolean
      unacknowledge?: boolean
      close?: boolean
      message?: string
      newSeverity?: Severity
      suppressForMinutes?: number
    },
  ): Promise<Problem> {
    return request(`/api/problems/${problemId}/acknowledge`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },

  hosts(hostClass?: string): Promise<HostSummary[]> {
    return request(`/api/hosts${hostClass ? `?hostClass=${hostClass}` : ''}`)
  },

  latestValues(hostId: number): Promise<LatestValue[]> {
    return request(`/api/monitoring/hosts/${hostId}/latest`)
  },

  cameras(itemKey = 'icmpping', group?: string): Promise<CameraStatus[]> {
    const params = new URLSearchParams({ itemKey })
    if (group) params.set('group', group)
    return request(`/api/monitoring/cameras?${params}`)
  },

  itemHistory(itemId: number, fromIso: string, toIso: string, points = 600): Promise<GraphSeries> {
    const params = new URLSearchParams({ from: fromIso, to: toIso, points: String(points) })
    return request(`/api/monitoring/items/${itemId}/history?${params}`)
  },

  templates(): Promise<TemplateSummary[]> {
    return request('/api/templates')
  },

  createHost(body: HostCreateRequest): Promise<HostSummary> {
    return request('/api/hosts', { method: 'POST', body: JSON.stringify(body) })
  },

  discoveryRules(): Promise<DiscoveryRule[]> {
    return request('/api/discovery/rules')
  },

  createDiscoveryRule(body: DiscoveryRuleRequest): Promise<DiscoveryRule> {
    return request('/api/discovery/rules', { method: 'POST', body: JSON.stringify(body) })
  },

  deleteDiscoveryRule(ruleId: number): Promise<void> {
    return request(`/api/discovery/rules/${ruleId}`, { method: 'DELETE' })
  },

  runDiscoveryRule(ruleId: number): Promise<void> {
    return request(`/api/discovery/rules/${ruleId}/run`, { method: 'POST' })
  },

  discoveredDevices(includeMonitored = false): Promise<DiscoveredDevice[]> {
    return request(`/api/discovery/devices?includeMonitored=${includeMonitored}`)
  },

  /** Turns a discovered device into a monitored host. */
  promoteDevice(deviceId: number, body: PromoteRequest = {}): Promise<HostSummary> {
    return request(`/api/discovery/devices/${deviceId}/host`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },
}
