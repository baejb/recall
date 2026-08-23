import type {
  AnswerFragment,
  ApiError,
  ApiResponse,
  ApproveResponse,
  CaptureRawResponse,
  CaptureResponse,
  CaptureStatusResponse,
  CatalogResponse,
  MemoryDetailResponse,
  MemoryPage,
  ReviewCountResponse,
  ReviewItemResponse,
  SettingsResponse,
  SettingsUpdateRequest,
} from './dto'

// 백엔드 호출 창구. 항상 상대경로 /api (dev는 vite 프록시, 배포는 nginx). 실패는 숨기지 않고 예외로.

/**
 * API 호출 실패를 타입으로 던진다 — 화면이 상태·코드로 분기할 수 있게.
 *
 * 전에는 문자열 하나만 던졌고, 그래서 `MemoryDetailPage` 가 `message.includes('→ 404')` 로 404를
 * 판별했다. 메시지 포맷을 바꾸는 순간(봉투 도입) 그 분기가 조용히 죽는다 — 실제로 죽었다.
 * 표시용 메시지와 분기용 식별자는 다른 축이므로 분리한다.
 */
export class ApiRequestError extends Error {
  readonly status: number
  /** 백엔드 `ErrorCode` 이름. 봉투 없이 실패한 경우(네트워크·프록시)엔 undefined. */
  readonly code?: string
  readonly field?: string
  readonly traceId?: string

  constructor(message: string, status: number, error?: ApiError) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = error?.code
    this.field = error?.field
    this.traceId = error?.traceId
  }

  /** 없는 리소스, 또는 남의 리소스(백엔드가 존재를 은닉해 404로 답한 경우). */
  get isNotFound(): boolean {
    return this.status === 404 || this.code === 'NOT_FOUND'
  }
}

/**
 * 응답 본문(봉투)에서 실패를 읽어 `ApiRequestError` 를 만든다. 봉투가 없거나 깨졌으면(프록시 에러 페이지,
 * 네트워크 단절) 상태 코드만으로 만든다 — 그 경우에도 조용히 성공으로 넘기지 않는다.
 */
function toRequestError(label: string, status: number, text: string): ApiRequestError {
  let error: ApiError | undefined
  try {
    error = text ? (JSON.parse(text) as ApiResponse<unknown>).error : undefined
  } catch {
    // 봉투가 아니면 상태 코드만으로 만든다.
  }
  const message = error?.message ?? `${label} → ${status}`
  const suffix = error ? ` (${error.code}, traceId=${error.traceId})` : ''
  return new ApiRequestError(`${message}${suffix}`, status, error)
}

/**
 * 백엔드는 성공·실패를 같은 봉투(`ApiResponse`)로 답한다: `{success:true,data}` 또는 `{success:false,error}`.
 * 그래서 이 함수 하나가 "봉투를 열고 실패면 던진다"를 담당하고, 호출부는 `data` 타입만 알면 된다.
 *
 * 전에는 성공이 원본 DTO, 실패는 RFC7807 ProblemDetail 이라 형태가 비대칭이었고, 그 결과
 * `updateSettings` 가 ProblemDetail 의 `detail` 을 뽑는 로직을 따로 갖고 있었다(같은 분기의 두 번째 사본).
 *
 * 실패 메시지에 code·traceId 를 함께 실어 던진다 — 사용자가 토스트에서 읽은 traceId 로 서버 로그를
 * 찾을 수 있어야 한다(조용한 실패 금지).
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  const text = await res.text()

  const label = `${init?.method ?? 'GET'} /api${path}`
  if (!res.ok) {
    throw toRequestError(label, res.status, text)
  }

  // 2xx 인데 본문이 JSON 이 아닐 수 있다(프록시가 200 + HTML 에러 페이지를 돌려주는 경우).
  // 그때 생 SyntaxError 를 던지면 `instanceof ApiRequestError` 로 분기하는 화면이 의도한 경로를
  // 못 탄다 — 실패 경로만 파싱을 방어하고 성공 경로는 무방비였던 비대칭을 없앤다.
  let envelope: ApiResponse<T> | undefined
  try {
    envelope = text ? (JSON.parse(text) as ApiResponse<T>) : undefined
  } catch {
    throw new ApiRequestError(`${label} → 응답이 JSON 이 아닙니다`, res.status)
  }
  if (envelope?.success === false) {
    throw toRequestError(label, res.status, text)
  }
  // data 는 본문 없는 성공(반려 등)에서 빠진다 — 그 경우 undefined 가 정상이다.
  return envelope?.data as T
}

/** 기억 상태. active=정상, archived=숨김(복원 가능), incorrect=폐기. */
export type MemoryStatus = 'active' | 'archived' | 'incorrect'

/** 기억 목록 조회 파라미터. type=유형 필터(ts|kn), status=조회 상태(기본 active), cursor=이전 페이지의 nextCursor. */
export interface MemoryListParams {
  q?: string
  type?: 'ts' | 'kn'
  status?: MemoryStatus
  cursor?: string
  limit?: number
}

/** GET /api/memories — 키셋 페이지네이션. 파라미터 없으면 활성 최신순 첫 페이지. */
export function getMemories(
  params: MemoryListParams = {},
  signal?: AbortSignal
): Promise<MemoryPage> {
  const sp = new URLSearchParams()
  if (params.q) sp.set('q', params.q)
  if (params.type) sp.set('type', params.type)
  if (params.status) sp.set('status', params.status)
  if (params.cursor) sp.set('cursor', params.cursor)
  if (params.limit) sp.set('limit', String(params.limit))
  const qs = sp.toString()
  return request<MemoryPage>(`/memories${qs ? `?${qs}` : ''}`, { signal })
}

/** PATCH /api/memories/{id}/status — 상태 전이(삭제 대신 상태 보존): archived(숨김)·incorrect(폐기)·active(복원). */
export function updateMemoryStatus(
  id: string | number,
  status: MemoryStatus
): Promise<MemoryDetailResponse> {
  return request<MemoryDetailResponse>(`/memories/${id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  })
}

/** GET /api/memories/{id} — 기억 단건 상세(구조화 필드 포함). 없으면 404 → request()가 예외로 던짐. */
export function getMemoryDetail(
  id: string | number,
  signal?: AbortSignal
): Promise<MemoryDetailResponse> {
  return request<MemoryDetailResponse>(`/memories/${id}`, { signal })
}

export function getReviews(signal?: AbortSignal): Promise<ReviewItemResponse[]> {
  return request<ReviewItemResponse[]>('/reviews', { signal })
}

export function getReviewCount(signal?: AbortSignal): Promise<ReviewCountResponse> {
  return request<ReviewCountResponse>('/reviews/count', { signal })
}

/** GET /api/captures/active — 검토함에 아직 안 올라온 처리중(PROCESSING)/실패(FAILED) 캡처. */
export function getActiveCaptures(signal?: AbortSignal): Promise<CaptureStatusResponse[]> {
  return request<CaptureStatusResponse[]>('/captures/active', { signal })
}

/** GET /api/captures/{id} — 원본 캡처(마스킹 완료된 원문). 없으면 404 → request()가 예외로 던짐. */
export function getCaptureRaw(
  id: string | number,
  signal?: AbortSignal
): Promise<CaptureRawResponse> {
  return request<CaptureRawResponse>(`/captures/${id}`, { signal })
}

export function createCapture(rawText: string, sourceType = 'chat'): Promise<CaptureResponse> {
  return request<CaptureResponse>('/captures', {
    method: 'POST',
    body: JSON.stringify({ rawText, sourceType }),
  })
}

export function approveReview(id: number): Promise<ApproveResponse> {
  return request<ApproveResponse>(`/reviews/${id}/approve`, { method: 'POST' })
}

/** 반려는 돌려줄 값이 없다 — 성공했다는 사실은 봉투의 `success` 가 말한다. */
export function rejectReview(id: number): Promise<void> {
  return request<void>(`/reviews/${id}/reject`, { method: 'POST' })
}

export function getSettings(signal?: AbortSignal): Promise<SettingsResponse> {
  return request<SettingsResponse>('/settings/models', { signal })
}

export function getCatalog(signal?: AbortSignal): Promise<CatalogResponse> {
  return request<CatalogResponse>('/settings/models/catalog', { signal })
}

/**
 * PUT /api/settings/models. 실패(주로 400)는 `request` 가 봉투의 `error.message` 를 뽑아 던지므로
 * 페이지가 그대로 토스트로 노출한다(조용한 실패 금지).
 *
 * 전에는 이 함수만 fetch 를 직접 열어 ProblemDetail 의 `detail` 을 뽑았다 — 응답 형태가 비대칭이라
 * 생긴 두 번째 사본이었고, 봉투가 하나가 되면서 필요가 없어졌다.
 */
export function updateSettings(body: SettingsUpdateRequest): Promise<SettingsResponse> {
  return request<SettingsResponse>('/settings/models', {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

/**
 * POST /api/query 를 SSE로 소비한다. EventSource는 GET 전용이라 fetch + ReadableStream으로 파싱한다.
 * 조각마다 onFragment 호출. AbortSignal로 중단 가능.
 */
export async function streamQuery(
  question: string,
  onFragment: (fragment: AnswerFragment) => void,
  signal?: AbortSignal
): Promise<void> {
  const res = await fetch('/api/query', {
    method: 'POST',
    // 성공은 SSE 스트림, 스트림 시작 전 실패는 JSON 에러 봉투다 — 둘 다 받겠다고 밝힌다.
    // 전에는 event-stream 만 보내서 서버의 봉투가 컨텐츠 협상에 걸려 빈 본문 500 으로 나갔다.
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream, application/json',
    },
    body: JSON.stringify({ question }),
    signal,
  })
  if (!res.ok) {
    // SSE 스트림이 시작되기 전 실패(주로 chat 미설정 409)는 **평소의 에러 봉투**로 온다 —
    // 백엔드 QueryController 가 스트림을 열기 전에 requireChat() 게이트로 던지기 때문이다.
    // 전에는 본문을 읽지 않고 상태 코드만 던져서, 사용자가 "설정하러 가야 한다"는 메시지도
    // traceId 도 보지 못했다(조용한 실패에 가까웠다).
    throw toRequestError('POST /api/query', res.status, await res.text().catch(() => ''))
  }
  if (!res.body) {
    throw new ApiRequestError('POST /api/query → 응답 본문이 없습니다', res.status)
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    // SSE 이벤트는 빈 줄로 구분된다.
    const events = buffer.split('\n\n')
    buffer = events.pop() ?? ''
    for (const block of events) {
      const dataLine = block.split('\n').find((l) => l.startsWith('data:'))
      if (!dataLine) continue
      try {
        onFragment(JSON.parse(dataLine.slice(5).trim()) as AnswerFragment)
      } catch {
        // 조각 파싱 실패는 건너뛴다(부분 청크). 다음 이벤트로.
      }
    }
  }
}
