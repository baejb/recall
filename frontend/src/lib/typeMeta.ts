import type { MemoryTypeKey } from '../types'

/** 유형별 표시 메타(라벨·색상 변수). 목업의 TYPES 맵. */
export const TYPE_META: Record<MemoryTypeKey, { label: string; short: string; varc: string }> = {
  ts: { label: '🔧 트러블슈팅', short: '트러블슈팅', varc: '--ts' },
  kn: { label: '📘 지식', short: '지식', varc: '--kn' },
}

export const TYPE_KEYS: MemoryTypeKey[] = ['ts', 'kn']
