import type {
  AnswerFragment,
  ApproveResponse,
  CaptureResponse,
  MemoryResponse,
  ReviewCountResponse,
  ReviewItemResponse,
} from './dto'

// 백엔드 호출 창구. 항상 상대경로 /api (dev는 vite 프록시, 배포는 nginx). 실패는 숨기지 않고 예외로.

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!res.ok) {
    const detail = await res.text().catch(() => '')
    throw new Error(`${init?.method ?? 'GET'} /api${path} → ${res.status} ${detail}`.trim())
  }
  // 204/빈 본문 방어
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export function getMemories(signal?: AbortSignal): Promise<MemoryResponse[]> {
  return request<MemoryResponse[]>('/memories', { signal })
}

export function getReviews(signal?: AbortSignal): Promise<ReviewItemResponse[]> {
  return request<ReviewItemResponse[]>('/reviews', { signal })
}

export function getReviewCount(signal?: AbortSignal): Promise<ReviewCountResponse> {
  return request<ReviewCountResponse>('/reviews/count', { signal })
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

export function rejectReview(id: number): Promise<{ status: string }> {
  return request<{ status: string }>(`/reviews/${id}/reject`, { method: 'POST' })
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
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ question }),
    signal,
  })
  if (!res.ok || !res.body) {
    throw new Error(`POST /api/query → ${res.status}`)
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
