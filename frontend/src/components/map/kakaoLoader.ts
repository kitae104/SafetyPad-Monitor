/** Minimal typings for the parts of the Kakao Maps JS SDK we use (avoids a dependency on third-party typings). */
/* eslint-disable @typescript-eslint/no-explicit-any */
export type KakaoNs = any

declare global {
  interface Window { kakao?: KakaoNs }
}

let pending: Promise<KakaoNs> | null = null

/** Loads the Kakao Maps SDK once. Rejects on script/network/key errors so callers can fall back to the key-less map. */
export function loadKakao(appKey: string, timeoutMs = 10_000): Promise<KakaoNs> {
  if (pending) return pending
  pending = new Promise<KakaoNs>((resolve, reject) => {
    const fail = (msg: string) => { pending = null; reject(new Error(msg)) }
    const ready = () => {
      try { window.kakao!.maps.load(() => resolve(window.kakao)) } catch (e) { fail(String(e)) }
    }
    if (window.kakao?.maps) return ready()
    const s = document.createElement('script')
    s.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(appKey)}&libraries=clusterer&autoload=false`
    s.async = true
    s.onload = ready
    s.onerror = () => { s.remove(); fail('카카오 지도 SDK를 불러오지 못했습니다') }
    document.head.appendChild(s)
    setTimeout(() => fail('카카오 지도 SDK 로딩 시간 초과'), timeoutMs)
  })
  return pending
}
