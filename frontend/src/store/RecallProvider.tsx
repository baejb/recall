import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import type { Memory, Review } from '../types'
import * as api from '../api/client'
import { toMemory, toReview } from '../api/adapter'
import { RecallContext, type RecallStore } from './recallContext'

interface Snapshot {
  memories: Memory[]
  reviews: Review[]
  reviewCount: number
}

/** 실 백엔드 연동 Provider. 마운트 시 목록을 로드하고, 변경 후 새로고침한다. */
export function RecallProvider({ children }: { children: ReactNode }) {
  const [memories, setMemories] = useState<Memory[]>([])
  const [reviews, setReviews] = useState<Review[]>([])
  const [reviewCount, setReviewCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // 서버 3종 목록을 병렬로 읽어 프론트 모델로 변환(setState 없음 — 순수 페칭).
  const fetchAll = useCallback(async (signal?: AbortSignal): Promise<Snapshot> => {
    const [mem, rev, count] = await Promise.all([
      api.getMemories(signal),
      api.getReviews(signal),
      api.getReviewCount(signal),
    ])
    return {
      memories: mem.map(toMemory),
      reviews: rev.map(toReview),
      reviewCount: count.pending,
    }
  }, [])

  const apply = useCallback((s: Snapshot) => {
    setMemories(s.memories)
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
    async (rawText: string) => {
      await api.createCapture(rawText)
      // 서버가 비동기로 추출→검토함에 올린다. 잠시 뒤 새로고침해 반영.
      await refresh()
    },
    [refresh]
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
      memories,
      reviews,
      reviewCount,
      loading,
      error,
      refresh,
      getMemory: (id) => memories.find((m) => m.id === id),
      getReview: (id) => reviews.find((r) => r.id === id),
      submitCapture,
      approveReview,
      rejectReview,
    }),
    [
      memories,
      reviews,
      reviewCount,
      loading,
      error,
      refresh,
      submitCapture,
      approveReview,
      rejectReview,
    ]
  )

  return <RecallContext.Provider value={store}>{children}</RecallContext.Provider>
}
