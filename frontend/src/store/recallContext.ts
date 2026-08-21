import { createContext } from 'react'
import type { Review } from '../types'

/**
 * submitCapture 결과. 서버가 비동기로 검토 항목을 만들기 때문에, 폴링으로 실제로
 * 찾았는지(found)를 함께 반환한다 — 호출부가 "찾음"과 "타임아웃"을 구분해 안내할 수 있게.
 */
export interface CaptureSubmitResult {
  captureId: string
  /** 폴링 기간 안에 해당 captureId의 검토 항목을 찾았는지. false면 아직 처리 중(타임아웃). */
  found: boolean
}

/**
 * 실 백엔드 연동 스토어 계약. GET /api 로 목록을 로드하고, 변경은 POST 후 새로고침한다.
 * 백엔드 미지원(재발·상태변경·카드 편집/나누기·원본 조회)은 이 계약에서 제외한다(화면에서 비활성).
 */
export interface RecallStore {
  reviews: Review[]
  reviewCount: number
  loading: boolean
  error: string | null

  /** reviews·count를 서버에서 다시 로드(기억 목록은 useMemoryList가 페이지네이션으로 별도 로드). */
  refresh(): Promise<void>
  getReview(id: string): Review | undefined

  /**
   * 붙여넣기 원문을 서버에 저장(POST /api/captures). 서버가 마스킹·추출을 비동기로 처리 →
   * 검토함에 나타남. 곧바로 목록을 읽으면 비어 있을 수 있어, 내부에서 새 항목이 보일 때까지
   * 짧게 폴링한 뒤 반환한다(found=false면 타임아웃 — 호출부가 안내 필요).
   */
  submitCapture(rawText: string): Promise<CaptureSubmitResult>
  /** 검토 승인 → memory 생성(POST). 생성된 memoryId 반환. */
  approveReview(reviewId: string): Promise<number>
  /** 검토 반려(POST). memory 생성 안 함. */
  rejectReview(reviewId: string): Promise<void>
}

export const RecallContext = createContext<RecallStore | null>(null)
