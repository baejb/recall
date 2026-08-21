import { useEffect, useRef, useState } from 'react'
import { getActiveCaptures } from '../api/client'
import type { CaptureStatusResponse } from '../api/dto'

const POLL_INTERVAL_MS = 2000

export interface ActiveCaptures {
  processing: CaptureStatusResponse[]
  failed: CaptureStatusResponse[]
}

/**
 * GET /api/captures/active 를 주기적으로 폴링해, 아직 검토함에 안 올라온 처리중/실패 캡처를
 * 노출한다(조용한 실패 금지 — 빈 검토함이 "할 일 없음"으로 오인되지 않게).
 *
 * 폴링 중 일시적 네트워크 오류는 마지막으로 성공한 값을 유지하고 무시한다(FAILED 목록
 * 자체가 이미 실패를 드러내는 표면이므로, 조회 오류까지 얹어 노이즈를 만들지 않는다).
 *
 * @param onProcessingDrop processing 개수가 이전 폴링보다 줄었을 때 호출(= 캡처 하나가
 *   끝나 검토함에 새 항목이 생겼을 가능성 → 호출부가 store.refresh() 등으로 반영).
 */
export function useActiveCaptures(onProcessingDrop?: () => void): ActiveCaptures {
  const [captures, setCaptures] = useState<CaptureStatusResponse[]>([])
  const prevProcessingCountRef = useRef<number | null>(null)
  // 매 폴링마다 최신 콜백을 쓰되, 콜백 자체는 폴링 effect의 의존성에 넣지 않아(ref 경유)
  // 인터벌이 불필요하게 재시작되지 않게 한다. ref 갱신은 렌더 중이 아니라 이 effect에서.
  const onProcessingDropRef = useRef(onProcessingDrop)
  useEffect(() => {
    onProcessingDropRef.current = onProcessingDrop
  }, [onProcessingDrop])

  useEffect(() => {
    let cancelled = false
    const ctrl = new AbortController()

    const poll = async () => {
      try {
        const next = await getActiveCaptures(ctrl.signal)
        if (cancelled) return
        const processingCount = next.filter((c) => c.status === 'PROCESSING').length
        const prev = prevProcessingCountRef.current
        if (prev !== null && processingCount < prev) {
          onProcessingDropRef.current?.()
        }
        prevProcessingCountRef.current = processingCount
        setCaptures(next)
      } catch {
        // 무시 — 마지막 성공 값 유지(언마운트 후 setState 방지는 cancelled 플래그로).
      }
    }

    void poll()
    const id = setInterval(() => void poll(), POLL_INTERVAL_MS)

    return () => {
      cancelled = true
      ctrl.abort()
      clearInterval(id)
    }
  }, [])

  return {
    processing: captures.filter((c) => c.status === 'PROCESSING'),
    failed: captures.filter((c) => c.status === 'FAILED'),
  }
}
