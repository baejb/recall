import type { MemoryTypeKey } from '../types'
import { TYPE_META } from '../lib/typeMeta'

/** 유형 태그(색 점 + 짧은 이름). */
export function TypeTag({ type }: { type: MemoryTypeKey }) {
  const meta = TYPE_META[type]
  return (
    <span className="type-tag">
      <span className="dot" style={{ background: `var(${meta.varc})` }} />
      {meta.short}
    </span>
  )
}
