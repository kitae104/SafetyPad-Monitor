import { useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { KeylessMap, type MapViewProps } from './KeylessMap'
import { KakaoMap } from './KakaoMap'

export type { MapViewProps }

/**
 * Map adapter boundary. Every provider implements MapViewProps.
 * `kakao` (needs a JavaScript key from the server config) renders real map tiles; anything else — no key, unknown
 * provider, SDK/network/domain failure — falls back to the key-less map instead of leaving the page blank.
 */
export function ViolationMap(props: MapViewProps) {
  const { config } = useAuth()
  const [failed, setFailed] = useState<string | null>(null)
  // Remount the key-less fallback when the point set changes so its view re-fits (user pan/zoom is intentionally reset).
  const key = `${props.points.length}:${props.points[0]?.id ?? 'none'}:${props.selectedDeviceId ?? ''}:${props.route?.range?.from ?? ''}:${props.route?.sufficient ? props.route.points.length : 0}`

  if (config?.map_provider === 'kakao' && config.map_js_key && !failed) {
    return <KakaoMap {...props} appKey={config.map_js_key} onError={(e) => setFailed(e.message)} />
  }
  return (
    <>
      {failed && <p role="alert" className="mb-1 text-xs text-amber-700">실제 지도를 불러오지 못해 대체 지도를 표시합니다: {failed}</p>}
      <KeylessMap key={key} {...props} />
    </>
  )
}
