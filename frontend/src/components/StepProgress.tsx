import { useStepProgress } from '../hooks/useStepProgress'

/** 스피너 + 순차 공개되는 단계 목록. 다 끝나면 onComplete 호출. */
export function StepProgress({
  steps,
  title,
  onComplete,
}: {
  steps: readonly string[]
  title: string
  onComplete: () => void
}) {
  const revealed = useStepProgress(steps, onComplete)
  return (
    <div className="loading-wrap">
      <div className="spinner" />
      <p style={{ fontWeight: 650, fontSize: 16, margin: 0 }}>{title}</p>
      <ul className="proglist">
        {steps.map((s, i) => (
          <li key={s} className={i < revealed ? '' : 'pending'}>
            <span className="m">{i + 1}</span> {s}
          </li>
        ))}
      </ul>
    </div>
  )
}
