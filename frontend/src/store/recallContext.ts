import { createContext } from 'react'
import type { Capture, MaskSpan, Memory, Review, ReviewCard } from '../types'

/**
 * mock 데이터 스토어 계약. 앱 전역에서 캡처·검토·기억을 공유·변경한다.
 * (전역 상태 라이브러리 대신 React Context — mock 단계라 의존성 추가 없이 충분.)
 */
export interface RecallStore {
  captures: Capture[]
  memories: Memory[]
  reviews: Review[]
  getCapture(id: string): Capture | undefined
  getMemory(id: string): Memory | undefined
  getReview(id: string): Review | undefined
  /** 붙여넣기 초안(마스킹 결과 + 추출 카드)으로 캡처와 검토 항목을 만든다. */
  addCaptureFromDraft(
    masked: string,
    spans: MaskSpan[],
    card: ReviewCard
  ): { captureId: string; reviewId: string }
  /** 검토 승인 — 편집된 카드들을 기억으로 확정하고 검토 항목을 제거. 생성된 기억 수 반환. */
  approveReview(reviewId: string, cards: ReviewCard[]): number
  /** 검토 반려 — 기억을 만들지 않고 검토 항목만 제거(원본은 보존). */
  rejectReview(reviewId: string): void
  /** 같은 문제를 또 겪음 — 재발 카운트 증가. */
  markRecur(memId: string): void
  /** 부분 해결이던 문제를 완전히 해결로 갱신 + 재발 기록. */
  resolveNow(memId: string): void
  /** 물어보기 결과에서 바로 재발 기록. */
  recordRecurFromQuery(memId: string): void
  /** 트러블슈팅 상태 순환(해결→부분→미해결). */
  cycleStatus(memId: string): void
  /** 보관(삭제 아님, 상태 전이). */
  archiveMemory(memId: string): void
}

export const RecallContext = createContext<RecallStore | null>(null)
