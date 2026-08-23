// 백엔드 응답/요청 DTO 미러링(실연동 계약). 백엔드 com.recall.*.dto 와 1:1.

/** GET /api/memories 의 items 요소 */
export interface MemoryResponse {
  id: number
  captureId: number
  type: string // "KNOWLEDGE" | "TROUBLESHOOTING"
  title: string
  summary: string | null
  /** 카드 내용의 상태 — 트러블슈팅 RESOLVED|PARTIAL|UNRESOLVED, 그 필드가 없는 유형(지식)은 null. */
  cardStatus: string | null
  status: string // active | archived | incorrect (기억 수명 상태)
  createdAt: string // ISO OffsetDateTime
}

/** 유형 탭 카운트(검색어가 걸리면 그 필터 기준). 첫 페이지 응답에만 실리고 이후 스크롤은 null. */
export interface MemoryCounts {
  total: number
  ts: number
  kn: number
}

/** GET /api/memories — 키셋 페이지네이션 한 페이지. nextCursor=null 이면 마지막. */
export interface MemoryPage {
  items: MemoryResponse[]
  nextCursor: string | null
  counts: MemoryCounts | null
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

/** GET /api/captures/active — 검토함에 아직 안 올라온 처리중/실패 캡처(조용한 실패 금지). */
export interface CaptureStatusResponse {
  id: number
  status: string // PROCESSING | FAILED
  sourceType: string
  failedStage: string | null // classify | extract | judge | review | null
  createdAt: string
}

/** proposed / structured 안의 KnowledgeCard(추출 스키마). */
export interface KnowledgeCard {
  title?: string
  summary?: string
  keywords?: string[]
  facts?: string[]
  document?: string
}

/** TroubleshootingCard 의 시도 한 건. */
export interface TroubleshootingAttempt {
  action?: string
  result?: string
  outcome?: string // failed | partial | worked | unknown
}

/**
 * proposed / structured 안의 TroubleshootingCard(추출 스키마).
 * 키 이름은 백엔드·PRD 표기(snake_case)를 그대로 미러링한다.
 */
export interface TroubleshootingCard {
  title?: string
  summary?: string
  keywords?: string[]
  symptom?: string
  error_message?: string
  error_signature?: string
  environment?: string
  attempts?: TroubleshootingAttempt[]
  root_cause?: string
  final_solution?: string
  status?: string // RESOLVED | PARTIAL | UNRESOLVED
}

/**
 * GET /api/memories/{id} — 기억 단건 상세(구조화 필드 포함). 없으면 404.
 *
 * `structured` 는 승인된 카드 전체(유형 무관)다 — 유형별 필드는 여기서 읽는다.
 * `keywords`·`facts`·`document` 는 knowledge 카드를 평면화한 레거시 필드다(백엔드도 그렇게 표기).
 */
export interface MemoryDetailResponse {
  id: number
  captureId: number
  type: string
  title: string
  summary: string | null
  keywords: string[]
  facts: string[]
  document: string | null
  structured: Record<string, unknown>
  status: string
  createdAt: string
}

/** GET /api/captures/{id} — 원본 캡처(마스킹 완료된 원문). 없으면 404. */
export interface CaptureRawResponse {
  id: number
  sourceType: string
  rawText: string
  createdAt: string
}

/**
 * GET /api/me — 현재 로그인 사용자. 세션이 없으면 이 호출이 401 로 실패한다.
 *
 * `bootstrapMode=true` 는 백엔드가 인증 없이(부트스트랩 모드) 돌고 있다는 뜻이다 — 모든 요청이
 * `app_user.id=1` 로 스코프된다. 화면이 그 사실을 숨기면 열려 있는 인스턴스가 정상처럼 보인다.
 */
export interface MeResponse {
  userId: number
  email: string
  displayName: string
  bootstrapMode: boolean
}

/**
 * 모든 REST 응답의 공통 봉투 — 성공은 `{success:true,data}`, 실패는 `{success:false,error}`.
 * 백엔드 `com.recall.common.web.ApiResponse` 를 미러링한다.
 *
 * SSE(`POST /api/query`)만 예외다 — 응답이 하나의 JSON 본문이 아니라 조각의 흐름이라 봉투를 씌우지 않는다.
 */
export interface ApiResponse<T> {
  success: boolean
  /** 본문 없는 성공(반려 등)에서는 빠진다. */
  data?: T
  error?: ApiError
}

/** 실패 응답 본문. `code` 는 분기용 안정 식별자(한국어 메시지 문자열을 비교하지 않게). */
export interface ApiError {
  code: string
  message: string
  /** 필드 단위 오류일 때만 채워진다. */
  field?: string
  /** 서버 로그와 상관시킬 수 있는 식별자 — 사용자가 보고할 수 있게 메시지에 함께 노출한다. */
  traceId: string
}
