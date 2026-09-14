import type { CameraState, Severity } from '../api/types'

/**
 * Severity presentation.
 *
 * Each level pairs a reserved status colour with a glyph shape and an
 * always-visible label. The status colours are chosen to read as severity
 * rather than to be told apart from one another by hue, so colour alone is
 * never the carrier -- which also keeps the interface legible to a colour-blind
 * reader, in print, and in forced-colours mode.
 *
 * Disaster reuses the critical hue and is distinguished by a filled badge
 * instead of an outlined one. Inventing a seventh red would produce two colours
 * nobody could reliably tell apart at exactly the moment it matters most.
 */
const SEVERITY_STYLE: Record<
  Severity,
  { label: string; color: string; glyph: 'circle' | 'square' | 'diamond'; filled: boolean }
> = {
  DISASTER: { label: 'Disaster', color: 'var(--status-critical)', glyph: 'diamond', filled: true },
  HIGH: { label: 'High', color: 'var(--status-critical)', glyph: 'diamond', filled: false },
  AVERAGE: { label: 'Average', color: 'var(--status-serious)', glyph: 'square', filled: false },
  WARNING: { label: 'Warning', color: 'var(--status-warning)', glyph: 'square', filled: false },
  INFORMATION: { label: 'Information', color: 'var(--status-info)', glyph: 'circle', filled: false },
  NOT_CLASSIFIED: { label: 'Not classified', color: 'var(--status-none)', glyph: 'circle', filled: false },
}

export const SEVERITY_ORDER: Severity[] = [
  'DISASTER',
  'HIGH',
  'AVERAGE',
  'WARNING',
  'INFORMATION',
  'NOT_CLASSIFIED',
]

export function severityLabel(severity: Severity): string {
  return SEVERITY_STYLE[severity].label
}

export function severityColor(severity: Severity): string {
  return SEVERITY_STYLE[severity].color
}

export function SeverityBadge({ severity }: { severity: Severity }) {
  const style = SEVERITY_STYLE[severity]
  return (
    <span
      className={`severity-badge${style.filled ? ' filled' : ''}`}
      style={{ color: style.color }}
      // Spelled out for a screen reader, which cannot see either the colour or
      // the shape.
      aria-label={`Severity: ${style.label}`}
    >
      <span className={`status-glyph ${style.glyph}`} style={{ background: 'currentColor' }} aria-hidden />
      <span className="severity-text">{style.label}</span>
    </span>
  )
}

/**
 * Camera state.
 *
 * Unknown is deliberately its own state rather than being folded into offline:
 * "we cannot tell" and "it is down" call for different responses, and showing
 * the first as the second sends an engineer to check a camera that is fine.
 */
const CAMERA_STYLE: Record<CameraState, { label: string; color: string; glyph: string }> = {
  ONLINE: { label: 'Online', color: 'var(--status-good)', glyph: 'circle' },
  OFFLINE: { label: 'Offline', color: 'var(--status-critical)', glyph: 'diamond' },
  UNKNOWN: { label: 'No data', color: 'var(--status-none)', glyph: 'square' },
}

export function CameraStateMark({ state }: { state: CameraState }) {
  const style = CAMERA_STYLE[state]
  return (
    <span className="status-mark" style={{ color: style.color }}>
      <span className={`status-glyph ${style.glyph}`} style={{ background: 'currentColor' }} aria-hidden />
      <span className="severity-text">{style.label}</span>
    </span>
  )
}

export function cameraStateClass(state: CameraState): string {
  return state.toLowerCase()
}
