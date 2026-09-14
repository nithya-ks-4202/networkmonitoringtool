import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { GraphSeries } from '../api/types'

/**
 * A metric over time.
 *
 * One series, so the title names it and no legend box is needed. The shaded
 * band is the min-to-max range within each bucket and the line is the average:
 * a bucket averaging 40% that briefly touched 100% is a very different picture
 * from one that sat flat at 40%, and a single averaged line hides exactly the
 * spike an operator is looking for.
 *
 * Never two y-axes. A second measure of a different scale belongs in a second
 * chart, because a dual axis lets the reader infer a relationship from where
 * the designer happened to put the zero.
 */
export function MetricChart({ series, height = 220 }: { series: GraphSeries; height?: number }) {
  if (series.points.length === 0) {
    return (
      <div className="chart-shell">
        <ChartHeader series={series} />
        <div className="empty">No data collected in this period</div>
      </div>
    )
  }

  const data = series.points.map((point) => ({
    time: new Date(point.clock).getTime(),
    avg: point.avg,
    // Recharts draws an Area from a [low, high] tuple, which is how the band
    // is rendered as one mark rather than two lines with a fill between them.
    band: [point.min, point.max] as [number, number],
  }))

  return (
    <div className="chart-shell">
      <ChartHeader series={series} />
      <ResponsiveContainer width="100%" height={height}>
        <ComposedChart data={data} margin={{ top: 4, right: 12, bottom: 4, left: 4 }}>
          {/* Recessive: the grid orients the eye, it does not compete with data. */}
          <CartesianGrid stroke="var(--gridline)" strokeDasharray="2 4" vertical={false} />

          <XAxis
            dataKey="time"
            type="number"
            scale="time"
            domain={['dataMin', 'dataMax']}
            tickFormatter={formatAxisTime}
            stroke="var(--axis)"
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            tickLine={false}
            minTickGap={44}
          />
          <YAxis
            stroke="var(--axis)"
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            tickLine={false}
            axisLine={false}
            width={52}
            tickFormatter={(value: number) => formatValue(value, series.units)}
          />

          <Tooltip
            content={<MetricTooltip units={series.units} />}
            cursor={{ stroke: 'var(--text-muted)', strokeWidth: 1, strokeDasharray: '3 3' }}
          />

          <Area
            dataKey="band"
            stroke="none"
            fill="var(--series-1)"
            fillOpacity={0.16}
            isAnimationActive={false}
          />
          <Line
            dataKey="avg"
            stroke="var(--series-1)"
            strokeWidth={2}
            dot={false}
            // Points arrive already bucketed by the server, so gaps are real
            // gaps in collection rather than artefacts of downsampling, and
            // connecting across them would draw data that does not exist.
            connectNulls={false}
            isAnimationActive={false}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}

function ChartHeader({ series }: { series: GraphSeries }) {
  return (
    <div className="chart-header">
      <h3 className="chart-title">{series.name}</h3>
      {series.units && <span className="chart-units">{series.units}</span>}
    </div>
  )
}

interface TooltipPayloadEntry {
  payload: { time: number; avg: number; band: [number, number] }
}

function MetricTooltip({
  active,
  payload,
  units,
}: {
  active?: boolean
  payload?: TooltipPayloadEntry[]
  units: string
}) {
  const entry = payload?.[0]?.payload
  if (!active || !entry) return null

  const [min, max] = entry.band
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-time">{new Date(entry.time).toLocaleString()}</div>
      <div className="chart-tooltip-row">
        <span
          className="status-glyph circle"
          style={{ background: 'var(--series-1)', boxShadow: 'none' }}
          aria-hidden
        />
        <span>avg {formatValue(entry.avg, units)}</span>
      </div>
      {min !== max && (
        <div className="chart-tooltip-row muted">
          <span style={{ width: 9 }} aria-hidden />
          <span>
            {formatValue(min, units)} – {formatValue(max, units)}
          </span>
        </div>
      )}
    </div>
  )
}

function formatAxisTime(value: number): string {
  return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/**
 * Renders a value in the unit the item declares.
 *
 * Bytes and bits are scaled because an interface counter in raw bits is an
 * unreadable number, and seconds below one are shown in milliseconds because
 * "0.004 s" is harder to compare at a glance than "4 ms".
 */
export function formatValue(value: number, units: string): string {
  if (!Number.isFinite(value)) return '–'

  if (units === 'B' || units === 'bps') {
    const scale = units === 'B' ? 1024 : 1000
    const suffixes = units === 'B' ? ['B', 'KiB', 'MiB', 'GiB', 'TiB'] : ['bps', 'Kbps', 'Mbps', 'Gbps', 'Tbps']
    let scaled = value
    let index = 0
    while (Math.abs(scaled) >= scale && index < suffixes.length - 1) {
      scaled /= scale
      index += 1
    }
    return `${trim(scaled)} ${suffixes[index]}`
  }

  if (units === 's') {
    if (Math.abs(value) < 1) return `${trim(value * 1000)} ms`
    if (Math.abs(value) < 60) return `${trim(value)} s`
    return `${trim(value / 60)} min`
  }

  if (units === 'uptime') {
    const days = Math.floor(value / 86_400)
    const hours = Math.floor((value % 86_400) / 3600)
    return days > 0 ? `${days}d ${hours}h` : `${hours}h`
  }

  if (units === '%') return `${trim(value)}%`

  return trim(value)
}

function trim(value: number): string {
  if (Number.isInteger(value)) return String(value)
  if (Math.abs(value) >= 100) return value.toFixed(0)
  if (Math.abs(value) >= 10) return value.toFixed(1)
  return value.toFixed(2)
}
