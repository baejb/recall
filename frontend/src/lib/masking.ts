import type { MaskSpan } from '../types'

// M0 마스킹(불변 원칙: 마스킹 우선)의 프론트 목업. 룰 기반으로 비밀 후보를 먼저 잡는다.
// 실제 마스킹은 백엔드 책임 — 여기선 붙여넣기 미리보기용 결정론 규칙만.

const SECRET_RE =
  /(DB_PASSWORD=|AWS_KEY=|API_KEY=|password=|token=)(\S+)|\b(sk-[A-Za-z0-9_-]{6,}|ghp_[A-Za-z0-9]{6,}|AKIA[A-Z0-9]{6,})\b/g

const MASK_TOKEN = '●●●●●●●'

export interface DetectedSecret {
  key: string
  val: string
}

export function detectSecrets(text: string): DetectedSecret[] {
  const found: DetectedSecret[] = []
  SECRET_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = SECRET_RE.exec(text)) !== null) {
    if (m[2]) found.push({ key: m[1].replace('=', ''), val: m[2] })
    else if (m[3]) found.push({ key: '키/토큰', val: m[3] })
  }
  return found
}

export function maskText(text: string, found: DetectedSecret[]): string {
  let out = text
  found.forEach((f) => {
    out = out.split(f.val).join(MASK_TOKEN)
  })
  return out
}

export function toSpans(found: DetectedSecret[]): MaskSpan[] {
  return found.map((f) => ({ key: f.key }))
}

export { MASK_TOKEN }
