import { useState } from 'react'
import type { QueryScope } from '../types'
import { useToast } from '../hooks/useToast'
import { StepProgress } from '../components/StepProgress'
import { QueryResult } from '../components/QueryResult'

const THINK_STEPS = [
  '질문 의도 파악',
  '내 기억 검색 (의미 + 키워드)',
  '새로 조사 (필요 시)',
  '비교 → 답 구성',
] as const

const SCOPES: { key: QueryScope; label: string }[] = [
  { key: '전체', label: '전체' },
  { key: 'ts', label: '🔧 트러블슈팅' },
  { key: 'kn', label: '📘 지식' },
]

const EXAMPLES = [
  { q: '그 도커 권한 에러 또 났어', label: '🔁 도커 권한 또…' },
  { q: 'CORS 프리플라이트 왜 막히지?', label: '🔁 CORS 또…' },
  { q: 'RRF가 뭐야?', label: '📘 RRF가 뭐야?' },
  { q: '쿠버네티스 인그레스 설정 어떻게 해?', label: '🆕 처음 보는 질문…' },
]

type Phase = 'home' | 'thinking' | 'result'

export function QueryPage() {
  const [phase, setPhase] = useState<Phase>('home')
  const [question, setQuestion] = useState('')
  const [scope, setScope] = useState<QueryScope>('전체')
  const [input, setInput] = useState('')
  const toast = useToast()

  const ask = (q: string) => {
    const trimmed = q.trim()
    if (!trimmed) {
      toast('질문을 입력해 주세요')
      return
    }
    setQuestion(trimmed)
    setPhase('thinking')
  }

  const backHome = () => {
    setPhase('home')
    setInput('')
  }

  if (phase === 'thinking') {
    return (
      <section className="screen">
        <StepProgress steps={THINK_STEPS} title="생각 중…" onComplete={() => setPhase('result')} />
      </section>
    )
  }

  if (phase === 'result') {
    return <QueryResult question={question} scope={scope} onBack={backHome} />
  }

  return (
    <section className="screen">
      <div className="eyebrow">물어보기</div>
      <h1 className="h1">무엇이든 묻거나, 그냥 붙여넣어 보세요</h1>
      <p className="lede">
        Recall이 먼저 내 기억을 뒤져요. <b>예전에 겪은 문제면 다시 풀지 않고</b> 그때 해결책·근거를
        꺼내주고, 처음이면 새로 풀어 저장까지 제안해요.
      </p>
      <div className="catfilter">
        <span
          style={{ fontSize: 12.5, color: 'var(--text-faint)', fontFamily: 'var(--font-mono)' }}
        >
          범위
        </span>
        {SCOPES.map((s) => (
          <button
            key={s.key}
            className={scope === s.key ? 'catf on' : 'catf'}
            onClick={() => setScope(s.key)}
          >
            {s.label}
          </button>
        ))}
      </div>
      <div className="card pad">
        <input
          type="text"
          value={input}
          placeholder="예: 그 도커 권한 에러 어떻게 풀었지?  /  RRF가 뭐야?"
          style={{ marginBottom: 12 }}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') ask(input)
          }}
        />
        <div className="between">
          <div className="row">
            {EXAMPLES.map((ex) => (
              <button key={ex.q} className="chipbtn" onClick={() => ask(ex.q)}>
                {ex.label}
              </button>
            ))}
          </div>
          <button className="btn primary" onClick={() => ask(input)}>
            물어보기
          </button>
        </div>
        <div className="note">
          <b>흐름</b>
          <span>
            질문 → 의도 파악 → (기억 검색 + 새로 조사) → 비교 → 답. 실제로 저장된 기억에서 찾아요.
          </span>
        </div>
      </div>
    </section>
  )
}
