import { createContext } from 'react'
import type { Memory, Review } from '../types'

/**
 * 실 백엔드 연동 스토어 계약. GET /api 로 목록을 로드하고, 변경은 POST 후 새로고침한다.
 * 백엔드 미지원(재발·상태변경·카드 편집/나누기·원본 조회)은 이 계약에서 제외한다(화면에서 비활성).
 */
export interface RecallStore {
  memories: Memory[]
  reviews: Review[]
  reviewCount: number
  loading: boolean
  error: string | null

  /** memories·reviews·count를 서버에서 다시 로드. */
  refresh(): Promise<void>
  /** 로드된 목록에서 단건 조회(단건 GET 엔드포인트가 없어 캐시로 조회). */
  getMemory(id: string): Memory | undefined
  getReview(id: string): Review | undefined

  /** 붙여넣기 원문을 서버에 저장(POST /api/captures). 서버가 마스킹·추출을 비동기로 처리 → 검토함에 나타남. */
  submitCapture(rawText: string): Promise<void>
  /** 검토 승인 → memory 생성(POST). 생성된 memoryId 반환. */
  approveReview(reviewId: string): Promise<number>
  /** 검토 반려(POST). memory 생성 안 함. */
  rejectReview(reviewId: string): Promise<void>
}

export const RecallContext = createContext<RecallStore | null>(null)
