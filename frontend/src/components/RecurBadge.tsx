import type { Memory } from '../types'

/** 재발 배지 — 2회 이상 마주친 문제에만 표시. */
export function RecurBadge({ memory }: { memory: Memory }) {
  if (!memory.hits || memory.hits <= 1) return null
  return (
    <span className="recur">
      재발 {memory.hits}회 · 마지막 {memory.lastSeen || memory.created}
    </span>
  )
}
