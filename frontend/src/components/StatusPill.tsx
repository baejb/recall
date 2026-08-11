import type { TsStatus } from '../types'

/** 트러블슈팅 해결 상태 배지(해결=녹색·부분=주황·미해결=빨강). */
export function StatusPill({ status }: { status: TsStatus }) {
  const cls = status === '해결' ? 'ok' : status === '부분' ? 'warn' : 'bad'
  return <span className={`pill ${cls}`}>{status}</span>
}
