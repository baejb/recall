import type { Memory, QueryScope, ReviewCard } from '../types'

// 목업의 검색·매칭 순수 로직. 실제로는 백엔드 하이브리드 검색이 담당할 부분의 클라이언트 흉내.

/** 메모리의 대표 요약 문장(유형별). */
export function memSummary(m: Memory): string {
  if (m.type !== 'ts') return m.kn.content
  return m.ts.finalSolution || m.ts.summary || m.ts.symptom
}

/** 매칭 근거: 질문에 키워드가 직접 들어있으면 '단어 일치', 아니면 '의미 유사'. */
export function matchReason(m: Memory, q: string): { cls: string; label: string } {
  const ql = (q || '').toLowerCase()
  const kws = (m.keywords || '')
    .toLowerCase()
    .split(/\s+/)
    .filter((k) => k.length >= 2)
  const kw = kws.some((k) => ql.indexOf(k) >= 0)
  return kw ? { cls: '', label: '단어 일치' } : { cls: 'sem', label: '의미 유사' }
}

/** 활성 메모리 중 질문·범위에 맞는 것을 찾고, 단어 일치·재발 많은 순으로 정렬. */
export function searchMemories(memories: Memory[], q: string, scope: QueryScope): Memory[] {
  const ql = q.toLowerCase()
  const tokens = ql.split(/\s+/).filter((t) => t.length >= 2)
  const hits = memories.filter((m) => {
    if (m.status !== 'active') return false
    if (scope !== '전체' && m.type !== scope) return false
    const hay = (m.title + ' ' + memSummary(m) + ' ' + (m.keywords || '')).toLowerCase()
    const kws = (m.keywords || '')
      .toLowerCase()
      .split(/\s+/)
      .filter((k) => k.length >= 2)
    const kwHit = kws.some((k) => ql.indexOf(k) >= 0)
    const tokHit = tokens.some((t) => hay.indexOf(t) >= 0)
    return kwHit || tokHit
  })
  return hits.slice().sort((a, b) => {
    const ra = matchReason(a, q).label === '단어 일치' ? 0 : 1
    const rb = matchReason(b, q).label === '단어 일치' ? 0 : 1
    if (ra !== rb) return ra - rb
    return (b.hits || 1) - (a.hits || 1)
  })
}

/** 내 기억 목록의 키워드/제목 검색. */
export function filterMemories(memories: Memory[], q: string): Memory[] {
  const query = q.trim().toLowerCase()
  const active = memories.filter((m) => m.status === 'active')
  if (!query) return active
  return active.filter((m) => {
    const hay = (m.title + ' ' + memSummary(m) + ' ' + (m.keywords || '')).toLowerCase()
    return query.split(/\s+/).some((t) => t && hay.indexOf(t) >= 0)
  })
}

/** 검토 카드가 기존 기억과 같은 문제인지(키워드 3개 이상 겹침) 탐지. */
export function findSimilarMemory(memories: Memory[], card: ReviewCard): Memory | null {
  const text = (
    (card.title || '') +
    ' ' +
    (card.ts?.symptom || '') +
    ' ' +
    (card.ts?.errorSignature || '') +
    ' ' +
    (card.kn?.content || '')
  ).toLowerCase()
  const toks = text.split(/[\s·(),→]+/).filter((t) => t.length >= 2)
  let best: Memory | null = null
  let bestN = 0
  memories.forEach((m) => {
    if (m.status !== 'active') return
    const kws = (m.keywords || '').toLowerCase()
    const n = toks.filter((t) => kws.indexOf(t) >= 0).length
    if (n > bestN) {
      bestN = n
      best = m
    }
  })
  return bestN >= 3 ? best : null
}
