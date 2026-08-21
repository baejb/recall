import type {
  KnowledgeCard,
  MemoryResponse,
  ReviewItemResponse,
  TroubleshootingAttempt,
  TroubleshootingCard,
} from './dto'
import type {
  Memory,
  MemoryTypeKey,
  Review,
  ReviewCard,
  TsAttempt,
  TsFields,
  TsStatus,
} from '../types'

// 백엔드 DTO → 프론트 모델 변환. 백엔드 미지원 필드(재발 횟수 등)는 기본값으로 채운다.

/** "KNOWLEDGE"|"TROUBLESHOOTING" → 프론트 'kn'|'ts'. */
export function toTypeKey(backendType: string | null | undefined): MemoryTypeKey {
  return backendType === 'TROUBLESHOOTING' ? 'ts' : 'kn'
}

/**
 * 카드의 status → 표시용 배지 값.
 * 모르는 값·미설정은 '미해결'로 둔다 — 해결됐다고 잘못 단정하는 쪽이 반대보다 위험하다(백엔드 정규화와 같은 규칙).
 */
export function toTsStatus(raw: string | null | undefined): TsStatus {
  if (raw === 'RESOLVED') return '해결'
  if (raw === 'PARTIAL') return '부분'
  return '미해결'
}

/** ISO OffsetDateTime → YYYY-MM-DD (표시용). */
function toDate(iso: string): string {
  return iso ? iso.slice(0, 10) : ''
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function strList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []
}

/** proposed(JSON 문자열) → 구조화 카드 맵. 깨진 JSON은 빈 카드로(화면을 비우지 않는다). */
function parseCard(proposed: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(proposed)
    return isObject(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

/** 구조화 카드 맵 → knowledge 카드(필드별로 좁혀 읽는다 — `as` 단언 없이). */
export function readKnowledgeCard(card: Record<string, unknown>): KnowledgeCard {
  return {
    title: str(card.title),
    summary: str(card.summary),
    keywords: strList(card.keywords),
    facts: strList(card.facts),
    document: str(card.document),
  }
}

/** 구조화 카드 맵 → troubleshooting 카드(필드별로 좁혀 읽는다). */
export function readTroubleshootingCard(card: Record<string, unknown>): TroubleshootingCard {
  return {
    title: str(card.title),
    summary: str(card.summary),
    keywords: strList(card.keywords),
    symptom: str(card.symptom),
    error_message: str(card.error_message),
    error_signature: str(card.error_signature),
    environment: str(card.environment),
    attempts: readAttempts(card.attempts),
    root_cause: str(card.root_cause),
    final_solution: str(card.final_solution),
    status: str(card.status),
  }
}

function readAttempts(value: unknown): TroubleshootingAttempt[] {
  if (!Array.isArray(value)) return []
  return value.filter(isObject).map((a) => ({
    action: str(a.action),
    result: str(a.result),
    outcome: str(a.outcome),
  }))
}

function toAttempt(attempt: TroubleshootingAttempt): TsAttempt {
  const outcome = attempt.outcome
  return {
    action: attempt.action ?? '',
    result: attempt.result ?? '',
    outcome:
      outcome === 'failed' || outcome === 'partial' || outcome === 'worked' ? outcome : 'unknown',
  }
}

/** troubleshooting 카드 → 화면 모델. 실패 시도까지 그대로 옮긴다(유실 금지). */
export function toTsFields(card: TroubleshootingCard): TsFields {
  return {
    summary: card.summary ?? '',
    symptom: card.symptom ?? '',
    errorMessage: card.error_message ?? '',
    errorSignature: card.error_signature ?? '',
    environment: card.environment ?? '',
    attempts: (card.attempts ?? []).map(toAttempt),
    rootCause: card.root_cause ?? '',
    finalSolution: card.final_solution ?? '',
    status: toTsStatus(card.status),
  }
}

export function toMemory(r: MemoryResponse): Memory {
  const type = toTypeKey(r.type)
  const content = r.summary ?? ''
  return {
    id: String(r.id),
    captureId: String(r.captureId),
    type,
    title: r.title,
    created: toDate(r.createdAt),
    status: r.status === 'active' ? 'active' : 'archived',
    firstSeen: toDate(r.createdAt),
    lastSeen: toDate(r.createdAt),
    hits: 1, // 재발 카운트는 백엔드 미지원 → 1 고정
    keywords: '', // MemoryResponse에 미노출
    // 목록 행은 title·summary 만 싣는다(유형별 필드는 상세 조회에서). 해결 상태만 실제 값(cardStatus)을 쓴다.
    ts: {
      summary: content,
      symptom: '',
      errorMessage: '',
      errorSignature: '',
      environment: '',
      attempts: [],
      rootCause: '',
      finalSolution: '',
      status: toTsStatus(r.cardStatus),
    },
    kn: { content },
  }
}

export function toReview(r: ReviewItemResponse): Review {
  const card = parseCard(r.proposed)
  const type = toTypeKey(r.memoryType)
  const kn = readKnowledgeCard(card)
  const reviewCard: ReviewCard = {
    type,
    title: str(card.title) || '(제목 없음)',
    ts: toTsFields(readTroubleshootingCard(card)),
    kn: {
      content: kn.document ?? '',
      summary: kn.summary,
      facts: kn.facts ?? [],
      keywords: kn.keywords ?? [],
    },
  }
  return { id: String(r.id), captureId: String(r.captureId), cards: [reviewCard] }
}
