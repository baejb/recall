import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import type { Review } from '../types'
import * as api from '../api/client'
import { toReview } from '../api/adapter'
import { RecallContext, type CaptureSubmitResult, type RecallStore } from './recallContext'

// 기억 목록은 더 이상 전역에 통째로 싣지 않는다(수천 건 대비). 목록 화면은 useMemoryList 가
// 키셋 페이지네이션으로 직접 로드하고, 여기서는 검토함·건수만 다룬다.
interface Snapshot {
  reviews: Review[]
  reviewCount: number
}

// 캡처 저장 직후 폴링 파라미터. 서버가 마스킹→추출→판정을 비동기로 처리하므로 즉시
// 새로고침하면 검토함이 비어 보인다(P2 버그). 짧은 간격으로 재확인해 그 창을 없앤다.
const REVIEW_POLL_INTERVAL_MS = 700
const REVIEW_POLL_TIMEOUT_MS = 10_000

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 새 captureId의 검토 항목이 나타날 때까지 짧게 재확인한다(최대 REVIEW_POLL_TIMEOUT_MS).
 * 폴링 중 일시적 네트워크 오류는 예외로 던지지 않고 다음 시도로 넘어간다 — 캡처 POST는 이미
 * 성공했으므로, 그 뒤 재확인 실패를 "저장 실패"로 오인시키면 안 된다(불변 원칙: 조용한 실패 금지
 * 와는 별개로, 거짓 실패도 금지).
 * @returns 마지막으로 성공한 스냅샷(한 번도 성공 못 했으면 null)과, 대상 항목을 찾았는지 여부.
 */
async function pollForReview(
  fetchAll: () => Promise<Snapshot>,
  captureId: string
): Promise<{ snapshot: Snapshot | null; found: boolean }> {
  const deadline = Date.now() + REVIEW_POLL_TIMEOUT_MS
  let snapshot: Snapshot | null = null
  let found = false
  for (;;) {
    try {
      snapshot = await fetchAll()
      found = snapshot.reviews.some((r) => r.captureId === captureId)
    } catch {
      // 무시하고 재시도(아래 deadline 체크로 결국 종료).
    }
    if (found || Date.now() >= deadline) break
    await delay(REVIEW_POLL_INTERVAL_MS)
  }
  return { snapshot, found }
}

/** 실 백엔드 연동 Provider. 마운트 시 목록을 로드하고, 변경 후 새로고침한다. */
export function RecallProvider({ children }: { children: ReactNode }) {
  const [reviews, setReviews] = useState<Review[]>([])
  const [reviewCount, setReviewCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // 검토함·건수를 병렬로 읽어 프론트 모델로 변환(setState 없음 — 순수 페칭).
  const fetchAll = useCallback(async (signal?: AbortSignal): Promise<Snapshot> => {
    const [rev, count] = await Promise.all([api.getReviews(signal), api.getReviewCount(signal)])
    return {
      reviews: rev.map(toReview),
      reviewCount: count.pending,
    }
  }, [])

  const apply = useCallback((s: Snapshot) => {
    setReviews(s.reviews)
    setReviewCount(s.reviewCount)
  }, [])

  // 수동 새로고침(재시도 버튼·변경 후). 이벤트 핸들러에서만 호출 → 즉시 loading 표시 OK.
  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      apply(await fetchAll())
    } catch (e) {
      setError(e instanceof Error ? e.message : '데이터를 불러오지 못했어요')
    } finally {
      setLoading(false)
    }
  }, [fetchAll, apply])

  // 마운트 로드: 이펙트 본문에서 동기 setState를 피하려 async IIFE로 감싼다(await 이후에만 setState).
  useEffect(() => {
    const ctrl = new AbortController()
    void (async () => {
      try {
        apply(await fetchAll(ctrl.signal))
        setError(null)
      } catch (e) {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : '데이터를 불러오지 못했어요')
      } finally {
        if (!ctrl.signal.aborted) setLoading(false)
      }
    })()
    return () => ctrl.abort()
  }, [fetchAll, apply])

  const submitCapture = useCallback(
    async (rawText: string): Promise<CaptureSubmitResult> => {
      const { captureId } = await api.createCapture(rawText)
      const id = String(captureId)
      // 서버가 비동기로 추출→검토함에 올린다(마스킹→LLM 추출→판정, 수 초 소요).
      // 바로 새로고침하면 목록이 비어 보이므로(P2), 새 항목이 뜰 때까지 짧게 폴링한다.
      const { snapshot, found } = await pollForReview(fetchAll, id)
      if (snapshot) {
        apply(snapshot)
      } else {
        // 폴링 내내 한 번도 성공적으로 못 읽었으면(네트워크 장애 등) 기존 refresh 경로로
        // 한 번 더 시도 — 실패 시 error 상태로 노출된다(조용한 실패 금지).
        await refresh()
      }
      return { captureId: id, found }
    },
    [fetchAll, apply, refresh]
  )

  const approveReview = useCallback(
    async (reviewId: string) => {
      const { memoryId } = await api.approveReview(Number(reviewId))
      await refresh()
      return memoryId
    },
    [refresh]
  )

  const rejectReview = useCallback(
    async (reviewId: string) => {
      await api.rejectReview(Number(reviewId))
      await refresh()
    },
    [refresh]
  )

  const store = useMemo<RecallStore>(
    () => ({
      reviews,
      reviewCount,
      loading,
      error,
      refresh,
      getReview: (id) => reviews.find((r) => r.id === id),
      submitCapture,
      approveReview,
      rejectReview,
    }),
    [reviews, reviewCount, loading, error, refresh, submitCapture, approveReview, rejectReview]
  )

  return <RecallContext.Provider value={store}>{children}</RecallContext.Provider>
}
