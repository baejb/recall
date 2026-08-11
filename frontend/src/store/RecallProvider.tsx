import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react'
import type { Capture, MaskSpan, Memory, Review, ReviewCard, TsStatus } from '../types'
import { SEED_CAPTURES, SEED_MEMORIES, SEED_REVIEWS } from '../mock/seed'
import { RecallContext, type RecallStore } from './recallContext'

const TS_STATUS_ORDER: TsStatus[] = ['해결', '부분', '미해결']

function cardKeywords(card: ReviewCard): string {
  return (card.title + ' ' + (card.ts.problem || '') + ' ' + (card.kn.content || '')).toLowerCase()
}

/** mock 데이터 스토어 Provider. seed로 초기화하고 액션으로 불변 갱신한다. */
export function RecallProvider({ children }: { children: ReactNode }) {
  const [captures, setCaptures] = useState<Capture[]>(SEED_CAPTURES)
  const [memories, setMemories] = useState<Memory[]>(SEED_MEMORIES)
  const [reviews, setReviews] = useState<Review[]>(SEED_REVIEWS)
  const seq = useRef(100)
  const nid = useCallback((p: string) => {
    seq.current += 1
    return p + seq.current
  }, [])

  const patchMemory = useCallback((memId: string, patch: (m: Memory) => Memory) => {
    setMemories((prev) => prev.map((m) => (m.id === memId ? patch(m) : m)))
  }, [])

  const addCaptureFromDraft = useCallback(
    (masked: string, spans: MaskSpan[], card: ReviewCard) => {
      const captureId = nid('c')
      const reviewId = nid('r')
      setCaptures((prev) => [...prev, { id: captureId, masked, spans, created: '오늘' }])
      setReviews((prev) => [...prev, { id: reviewId, captureId, cards: [card] }])
      return { captureId, reviewId }
    },
    [nid]
  )

  const approveReview = useCallback(
    (reviewId: string, cards: ReviewCard[]) => {
      const review = reviews.find((r) => r.id === reviewId)
      const captureId = review ? review.captureId : ''
      const created: Memory[] = cards.map((c) => ({
        id: nid('m'),
        captureId,
        type: c.type,
        title: c.title || '(제목 없음)',
        created: '오늘',
        status: 'active',
        firstSeen: '오늘',
        lastSeen: '오늘',
        hits: 1,
        keywords: cardKeywords(c),
        ts: c.ts,
        kn: c.kn,
      }))
      setMemories((prev) => [...created, ...prev])
      setReviews((prev) => prev.filter((r) => r.id !== reviewId))
      return created.length
    },
    [reviews, nid]
  )

  const rejectReview = useCallback((reviewId: string) => {
    setReviews((prev) => prev.filter((r) => r.id !== reviewId))
  }, [])

  const markRecur = useCallback(
    (memId: string) =>
      patchMemory(memId, (m) => ({ ...m, hits: (m.hits || 1) + 1, lastSeen: '오늘' })),
    [patchMemory]
  )

  const recordRecurFromQuery = markRecur

  const resolveNow = useCallback(
    (memId: string) =>
      patchMemory(memId, (m) =>
        m.type === 'ts'
          ? { ...m, ts: { ...m.ts, status: '해결' }, hits: (m.hits || 1) + 1, lastSeen: '오늘' }
          : m
      ),
    [patchMemory]
  )

  const cycleStatus = useCallback(
    (memId: string) =>
      patchMemory(memId, (m) => {
        if (m.type !== 'ts') return m
        const next = TS_STATUS_ORDER[(TS_STATUS_ORDER.indexOf(m.ts.status) + 1) % 3]
        return { ...m, ts: { ...m.ts, status: next } }
      }),
    [patchMemory]
  )

  const archiveMemory = useCallback(
    (memId: string) => patchMemory(memId, (m) => ({ ...m, status: 'archived' })),
    [patchMemory]
  )

  const store = useMemo<RecallStore>(
    () => ({
      captures,
      memories,
      reviews,
      getCapture: (id) => captures.find((c) => c.id === id),
      getMemory: (id) => memories.find((m) => m.id === id),
      getReview: (id) => reviews.find((r) => r.id === id),
      addCaptureFromDraft,
      approveReview,
      rejectReview,
      markRecur,
      resolveNow,
      recordRecurFromQuery,
      cycleStatus,
      archiveMemory,
    }),
    [
      captures,
      memories,
      reviews,
      addCaptureFromDraft,
      approveReview,
      rejectReview,
      markRecur,
      resolveNow,
      recordRecurFromQuery,
      cycleStatus,
      archiveMemory,
    ]
  )

  return <RecallContext.Provider value={store}>{children}</RecallContext.Provider>
}
