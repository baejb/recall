// 백엔드 응답/요청 DTO 미러링(실연동 계약). 백엔드 com.recall.*.dto 와 1:1.

/** GET /api/memories */
export interface MemoryResponse {
  id: number
  captureId: number
  type: string // "KNOWLEDGE" | "TROUBLESHOOTING"
  title: string
  summary: string | null
  status: string // active | superseded | incorrect
  createdAt: string // ISO OffsetDateTime
}

/** GET /api/reviews */
export interface ReviewItemResponse {
  id: number
  captureId: number
  judgement: string // NEW | RECURRENCE | SUPPLEMENT | CONFLICT
  targetMemoryId: number | null
  judgeReason: string | null
  memoryType: string | null
  status: string // pending | approved | edited | rejected
  proposed: string // KnowledgeCard JSON 문자열
  createdAt: string
}

/** POST /api/captures 요청/응답 */
export interface CaptureRequest {
  sourceType?: string
  rawText: string
}
export interface CaptureResponse {
  captureId: number
  status: string
}

/** POST /api/reviews/{id}/approve */
export interface ApproveResponse {
  memoryId: number
}

/** GET /api/reviews/count */
export interface ReviewCountResponse {
  pending: number
}

/** POST /api/query SSE 조각(event: answer) */
export interface AnswerFragment {
  text: string
  memoryId: number | null
}

/** proposed / structured 안의 KnowledgeCard(추출 스키마). */
export interface KnowledgeCard {
  title?: string
  summary?: string
  keywords?: string[]
  facts?: string[]
  document?: string
}
