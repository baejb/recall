import type { Memory } from '../types'

/** 재발 타임라인 — 처음 저장 시점과 가장 최근 재발을 잇는다. */
export function RecurTimeline({ memory }: { memory: Memory }) {
  return (
    <div className="timeline">
      <div className="ev first">
        <span className="d">{memory.firstSeen || memory.created}</span>처음 만나 저장
      </div>
      <div className="ev">
        <span className="d">{memory.lastSeen}</span>
        <b>가장 최근 재발</b> — 이 기억으로 되찾음
      </div>
    </div>
  )
}
