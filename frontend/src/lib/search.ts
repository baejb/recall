import type { Memory } from '../types'

// 실제 검색은 백엔드 하이브리드 검색이 담당한다. 여기엔 목록 표시용 요약 헬퍼만 둔다.
// (과거 목업 검색 로직 matchReason·searchMemories·filterMemories·findSimilarMemory 는
//  어디서도 쓰이지 않고 adapter 가 keywords 를 ''로 고정해 살아나도 오작동하므로 제거했다.)

/** 메모리의 대표 요약 문장(유형별). */
export function memSummary(m: Memory): string {
  if (m.type !== 'ts') return m.kn.content
  return m.ts.finalSolution || m.ts.summary || m.ts.symptom
}
