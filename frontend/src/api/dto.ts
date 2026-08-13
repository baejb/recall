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

/** GET /api/settings/models · PUT 응답 — 실제 키는 절대 내려오지 않는다(apiKeyConfigured 불리언만). */
export type EmbeddingStatus = 'READY' | 'REINDEXING' | 'FAILED'

export interface ModelSettings {
  provider: string
  model: string
  apiKeyConfigured: boolean
  baseUrl: string | null
}

export interface EmbeddingModelSettings extends ModelSettings {
  status: EmbeddingStatus
}

export interface SettingsResponse {
  chat: ModelSettings
  embedding: EmbeddingModelSettings
}

/** PUT /api/settings/models 요청. 모든 필드 선택 — 생략/공백 = 변경 없음. apiKey는 사용자가 입력했을 때만. */
export interface ModelUpdate {
  provider?: string
  model?: string
  apiKey?: string
  baseUrl?: string
}

export interface SettingsUpdateRequest {
  chat?: ModelUpdate
  embedding?: ModelUpdate
}

/** GET /api/settings/models/catalog — 역할별 provider→모델 목록. 맵 키 = 그 역할에 허용된 provider. */
export interface CatalogResponse {
  chatModels: Record<string, string[]>
  embeddingModels: Record<string, string[]>
}

/** proposed / structured 안의 KnowledgeCard(추출 스키마). */
export interface KnowledgeCard {
  title?: string
  summary?: string
  keywords?: string[]
  facts?: string[]
  document?: string
}
