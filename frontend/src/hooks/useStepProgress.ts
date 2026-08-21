import { useEffect, useRef, useState } from 'react'

const STEP_INTERVAL = 520
const DONE_DELAY = 350

/**
 * 단계 라벨을 하나씩 순차 공개하고, 다 끝나면 onComplete를 호출한다.
 * mock 단계 전용 진행 시뮬레이션(실제 SSE/비동기 잡의 자리표시). 언마운트 시 타이머 정리(조용한 실패 방지).
 *
 * @param steps 안정적인 참조여야 한다(컴포넌트 밖 상수 권장).
 * @returns 지금까지 공개된 단계 수
 */
export function useStepProgress(steps: readonly string[], onComplete: () => void): number {
  const [revealed, setRevealed] = useState(0)
  const onCompleteRef = useRef(onComplete)

  // 최신 콜백을 ref에 반영(렌더 중 ref 수정 금지 규칙 회피).
  useEffect(() => {
    onCompleteRef.current = onComplete
  }, [onComplete])

  useEffect(() => {
    let i = 0
    const timers: number[] = []
    const tick = () => {
      if (i >= steps.length) {
        timers.push(window.setTimeout(() => onCompleteRef.current(), DONE_DELAY))
        return
      }
      i += 1
      setRevealed(i)
      timers.push(window.setTimeout(tick, STEP_INTERVAL))
    }
    // 첫 단계는 한 박자 뒤 공개 — effect 내 동기 setState를 피한다.
    timers.push(window.setTimeout(tick, STEP_INTERVAL))
    return () => timers.forEach((t) => window.clearTimeout(t))
  }, [steps])

  return revealed
}
