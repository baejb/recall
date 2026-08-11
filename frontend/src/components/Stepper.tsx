import { Fragment } from 'react'

/** 붙여넣기 4단계 진행 표시. */
const CAPTURE_STEPS = ['1 붙여넣기', '2 마스킹 확인', '3 AI 정리', '4 검토·승인'] as const

export function Stepper({ current }: { current: number }) {
  return (
    <div className="stepper">
      {CAPTURE_STEPS.map((s, i) => (
        <Fragment key={s}>
          {i > 0 && <span className="arr">→</span>}
          <span className={`st ${i < current ? 'done' : i === current ? 'on' : ''}`}>{s}</span>
        </Fragment>
      ))}
    </div>
  )
}
