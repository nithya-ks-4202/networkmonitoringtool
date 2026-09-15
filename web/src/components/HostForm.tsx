import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { HostClass, HostDetail, TemplateSummary } from '../api/types'

/**
 * The fields common to adding a host by hand and to confirming one that
 * discovery found.
 *
 * <p>One component rather than two forms, because the second is the first
 * with the boxes already filled in. Keeping them separate is how the two
 * paths drift until a camera added by hand and the same camera added from a
 * sweep end up configured differently.
 */

const HOST_CLASSES: { value: HostClass; label: string }[] = [
  { value: 'CAMERA', label: 'Camera' },
  { value: 'NETWORK_DEVICE', label: 'Network device' },
  { value: 'SERVER', label: 'Server' },
  { value: 'PRINTER', label: 'Printer' },
  { value: 'UPS', label: 'UPS' },
  { value: 'STORAGE', label: 'Storage' },
  { value: 'HYPERVISOR', label: 'Hypervisor' },
  { value: 'APPLICATION', label: 'Application' },
  { value: 'GENERIC', label: 'Generic' },
]

/**
 * Camera settings that live in macros.
 *
 * <p>Surfaced as real fields rather than left to a generic macro editor.
 * The stream path is the single most common reason a healthy camera raises
 * an alert -- the default is Hikvision's, and an Axis or Dahua on that path
 * will fail its RTSP check forever while answering ping perfectly.
 */
const CAMERA_PATH_EXAMPLES = [
  { label: 'Hikvision', path: '/Streaming/Channels/101' },
  { label: 'Axis', path: '/axis-media/media.amp' },
  { label: 'Dahua', path: '/cam/realmonitor?channel=1&subtype=0' },
]

export interface HostFormValues {
  host: string
  name: string
  hostClass: HostClass
  address: string
  templates: string[]
  cameraPath: string
  cameraUser: string
  cameraPassword: string
  /**
   * Macros the form does not show, carried through untouched.
   *
   * <p>The update endpoint replaces the macro set wholesale, so a macro
   * missing from what is submitted is deleted. Without this, editing a host's
   * address through a form that knows about three macros would silently
   * discard every other one it had.
   */
  otherMacros: Record<string, string>
}

export function emptyHostForm(): HostFormValues {
  return {
    host: '',
    name: '',
    hostClass: 'CAMERA',
    address: '',
    templates: [],
    cameraPath: '',
    cameraUser: '',
    cameraPassword: '',
    otherMacros: {},
  }
}

/** Fills the form from a host as the API returns it. */
export function hostFormFrom(detail: HostDetail): HostFormValues {
  const remaining = { ...detail.macros }
  const take = (macro: string): string => {
    const value = remaining[macro] ?? ''
    delete remaining[macro]
    return value
  }
  return {
    host: detail.summary.host,
    // The server defaults the display name to the technical one, so showing
    // it back as though it had been typed would make every host look as if it
    // had been given one deliberately.
    name: detail.summary.name === detail.summary.host ? '' : detail.summary.name,
    hostClass: detail.summary.hostClass,
    address: detail.summary.address,
    templates: detail.templates,
    cameraPath: take('{$CAMERA.RTSP.PATH}'),
    cameraUser: take('{$CAMERA.USER}'),
    // Arrives as ****** for a secret macro, and means "unchanged" when sent
    // back that way.
    cameraPassword: take('{$CAMERA.PASSWORD}'),
    otherMacros: remaining,
  }
}

/**
 * Turns the form's camera fields into the macros the template reads.
 *
 * <p>Emitted on their values rather than on the host's class: a host that was
 * a camera and has been reclassified still holds its camera settings, and
 * dropping them because a select changed is a deletion nobody asked for.
 */
export function macrosFrom(values: HostFormValues): Record<string, string> {
  const macros: Record<string, string> = { ...values.otherMacros }
  if (values.cameraPath.trim()) macros['{$CAMERA.RTSP.PATH}'] = values.cameraPath.trim()
  if (values.cameraUser.trim()) macros['{$CAMERA.USER}'] = values.cameraUser.trim()
  if (values.cameraPassword) macros['{$CAMERA.PASSWORD}'] = values.cameraPassword
  return macros
}

interface Props {
  values: HostFormValues
  onChange: (values: HostFormValues) => void
  /** Hidden when the address is fixed, as it is for a discovered device. */
  addressEditable?: boolean
  /**
   * Editing has its own Templates panel, which links and unlinks for real.
   * Two controls for one thing, disagreeing about what is selected, is worse
   * than one in the wrong place.
   */
  showTemplates?: boolean
  mode?: 'create' | 'edit'
}

export function HostForm({
  values,
  onChange,
  addressEditable = true,
  showTemplates = true,
  mode = 'create',
}: Props) {
  const { data: templates } = useQuery({
    queryKey: ['templates'],
    queryFn: () => api.templates(),
    // Templates are seeded by migration and change only on upgrade, so this
    // is fetched once and reused rather than re-requested per render.
    staleTime: 10 * 60_000,
  })

  const set = <K extends keyof HostFormValues>(key: K, value: HostFormValues[K]) =>
    onChange({ ...values, [key]: value })

  const toggleTemplate = (name: string) => {
    const next = values.templates.includes(name)
      ? values.templates.filter((t) => t !== name)
      : [...values.templates, name]
    set('templates', next)
  }

  return (
    <>
      <div className="row" style={{ gap: 14 }}>
        <div className="field" style={{ flex: 1 }}>
          <label htmlFor="host-technical">
            Host name <span className="muted">— used in trigger expressions</span>
          </label>
          <input
            id="host-technical"
            className="control"
            value={values.host}
            placeholder="cam-lobby-01"
            onChange={(e) => set('host', e.target.value)}
          />
        </div>
        <div className="field" style={{ flex: 1 }}>
          <label htmlFor="host-visible">
            Display name <span className="muted">— optional</span>
          </label>
          <input
            id="host-visible"
            className="control"
            value={values.name}
            placeholder="Lobby camera"
            onChange={(e) => set('name', e.target.value)}
          />
        </div>
      </div>

      <div className="row" style={{ gap: 14 }}>
        {addressEditable && (
          <div className="field" style={{ flex: 1 }}>
            <label htmlFor="host-address">IP address or hostname</label>
            <input
              id="host-address"
              className="control"
              value={values.address}
              placeholder="10.30.5.40"
              onChange={(e) => set('address', e.target.value)}
            />
          </div>
        )}
        <div className="field" style={{ flex: 1 }}>
          <label htmlFor="host-class">Class</label>
          <select
            id="host-class"
            className="control"
            value={values.hostClass}
            onChange={(e) => set('hostClass', e.target.value as HostClass)}
          >
            {HOST_CLASSES.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {showTemplates && (
      <div className="field">
        <label>
          Templates <span className="muted">— what gets monitored</span>
        </label>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {(templates ?? []).map((template: TemplateSummary) => (
            <label
              key={template.id}
              style={{ display: 'flex', gap: 9, alignItems: 'flex-start', cursor: 'pointer' }}
            >
              <input
                type="checkbox"
                checked={values.templates.includes(template.name)}
                onChange={() => toggleTemplate(template.name)}
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
          {templates && templates.length === 0 && (
            <div className="muted">No templates are defined.</div>
          )}
        </div>
      </div>
      )}

      {values.hostClass === 'CAMERA' && <CameraFields values={values} set={set} mode={mode} />}
    </>
  )
}

function CameraFields({
  values,
  set,
  mode,
}: {
  values: HostFormValues
  set: <K extends keyof HostFormValues>(key: K, value: HostFormValues[K]) => void
  mode: 'create' | 'edit'
}) {
  return (
    <div className="card" style={{ background: 'transparent' }}>
      <div className="card-title">Camera settings</div>

      <div className="field">
        <label htmlFor="camera-path">Stream path</label>
        <input
          id="camera-path"
          className="control"
          value={values.cameraPath}
          placeholder="/Streaming/Channels/101"
          onChange={(e) => set('cameraPath', e.target.value)}
        />
        <div className="muted" style={{ fontSize: 12 }}>
          Wrong here and the stream check fails on a perfectly healthy camera.
          Leave blank for the Hikvision default.{' '}
          {CAMERA_PATH_EXAMPLES.map((example) => (
            <button
              key={example.label}
              type="button"
              className="button ghost"
              style={{ padding: '1px 7px', fontSize: 12, marginRight: 5 }}
              onClick={() => set('cameraPath', example.path)}
            >
              {example.label}
            </button>
          ))}
        </div>
      </div>

      <div className="row" style={{ gap: 14 }}>
        <div className="field" style={{ flex: 1 }}>
          <label htmlFor="camera-user">RTSP / ONVIF username</label>
          <input
            id="camera-user"
            className="control"
            value={values.cameraUser}
            autoComplete="off"
            onChange={(e) => set('cameraUser', e.target.value)}
          />
        </div>
        <div className="field" style={{ flex: 1 }}>
          <label htmlFor="camera-password">Password</label>
          <input
            id="camera-password"
            className="control"
            type="password"
            value={values.cameraPassword}
            autoComplete="new-password"
            onChange={(e) => set('cameraPassword', e.target.value)}
          />
          {mode === 'edit' && (
            // The stored password is never sent to the browser, so the field
            // holds a placeholder. Saying so matters: an operator who clears
            // it expecting "no change" would remove the camera's credentials
            // and break every authenticated check on it.
            <div className="muted" style={{ fontSize: 12 }}>
              Leave as it is to keep the stored password. Clearing this field removes it.
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * Suggests a technical name from the display name.
 *
 * <p>Only while the field is untouched: silently rewriting something an
 * operator typed is worse than leaving it blank.
 */
export function useSuggestedHostName(
  values: HostFormValues,
  onChange: (values: HostFormValues) => void,
  enabled: boolean,
) {
  useEffect(() => {
    if (!enabled || !values.name.trim()) return
    const suggestion = values.name
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9._-]+/g, '-')
      .replace(/^-+|-+$/g, '')
    if (suggestion && suggestion !== values.host) {
      onChange({ ...values, host: suggestion })
    }
    // onChange is recreated per render by the parent; depending on it here
    // would re-run this on every keystroke and fight the operator's edits.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [values.name, enabled])
}
