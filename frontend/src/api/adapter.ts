import type { KnowledgeCard, MemoryResponse, ReviewItemResponse } from './dto'
import type { Memory, MemoryTypeKey, Review, ReviewCard } from '../types'

// 백엔드 DTO → 프론트 모델 변환. 백엔드 미지원 필드(ts·재발·keywords 등)는 기본값으로 채운다.

/** "KNOWLEDGE"|"TROUBLESHOOTING" → 프론트 'kn'|'ts'. */
export function toTypeKey(backendType: string | null | undefined): MemoryTypeKey {
  return backendType === 'TROUBLESHOOTING' ? 'ts' : 'kn'
}

/** ISO OffsetDateTime → YYYY-MM-DD (표시용). */
function toDate(iso: string): string {
  return iso ? iso.slice(0, 10) : ''
}

function parseCard(proposed: string): KnowledgeCard {
  try {
    return JSON.parse(proposed) as KnowledgeCard
  } catch {
    return {}
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
    ts: { problem: content, tried: '', solution: '', status: '미해결' },
    kn: { content },
  }
}

export function toReview(r: ReviewItemResponse): Review {
  const card = parseCard(r.proposed)
  const type = toTypeKey(r.memoryType)
  const content = card.document ?? card.summary ?? ''
  const reviewCard: ReviewCard = {
    type,
    title: card.title ?? '(제목 없음)',
    ts: { problem: content, tried: '', solution: '', status: '미해결' },
    kn: {
      content: card.document ?? '',
      summary: card.summary,
      facts: card.facts ?? [],
      keywords: card.keywords ?? [],
    },
  }
  return { id: String(r.id), captureId: String(r.captureId), cards: [reviewCard] }
}
