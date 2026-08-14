import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useToast } from '../hooks/useToast'
import { QueryResult } from '../components/QueryResult'

const EXAMPLES = [
  { q: '도커 권한 에러 어떻게 풀었지?', label: '도커 권한 에러…' },
  { q: 'CORS 프리플라이트 왜 막히지?', label: 'CORS 프리플라이트…' },
  { q: 'RRF가 뭐야?', label: 'RRF가 뭐야?' },
]

type Phase = 'home' | 'result'

export function QueryPage() {
  // 내 기억 검색 무결과 → "물어보기" 브릿지: ?ask= 로 넘어오면 바로 그 질문의 결과 화면으로 시작한다.
  // (이펙트 동기 setState 회피를 위해 파라미터를 초기 state로 읽는다.)
  const [searchParams] = useSearchParams()
  const preset = (searchParams.get('ask') ?? '').trim()
  const [phase, setPhase] = useState<Phase>(preset ? 'result' : 'home')
  const [question, setQuestion] = useState(preset)
  const [input, setInput] = useState('')
  const toast = useToast()

  const ask = (q: string) => {
    const trimmed = q.trim()
    if (!trimmed) {
      toast('질문을 입력해 주세요')
      return
    }
    setQuestion(trimmed)
    setPhase('result')
  }

  const backHome = () => {
    setPhase('home')
    setInput('')
  }

  if (phase === 'result') {
    // key={question} — 새 질문마다 재마운트해 스트리밍 상태를 깨끗이 초기화.
    return <QueryResult key={question} question={question} onBack={backHome} />
  }

  return (
    <section className="screen">
      <div className="eyebrow">물어보기</div>
      <h1 className="h1">내 기억에 물어보세요</h1>
      <p className="lede">
        저장된 기억을 뒤져 <b>근거와 함께</b> 답해요. 기억에 없으면 지어내지 않고 "기록 없음"으로
        남겨요.
      </p>

      <div className="card pad">
        <input
          type="text"
          className="askinput"
          value={input}
          placeholder="예: 그 도커 권한 에러 어떻게 풀었지?"
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') ask(input)
          }}
        />
        <div className="between" style={{ marginTop: 14 }}>
          <span className="eyebrow">Enter로 바로 질문</span>
          <button className="btn primary" onClick={() => ask(input)}>
            물어보기
          </button>
        </div>
      </div>

      <div className="eyebrow" style={{ margin: '24px 0 10px' }}>
        이렇게 물어봐요
      </div>
      <div className="row">
        {EXAMPLES.map((ex) => (
          <button key={ex.q} className="chipbtn" onClick={() => ask(ex.q)}>
            {ex.label}
          </button>
        ))}
      </div>

      <div className="provline" style={{ marginTop: 26 }}>
        <span className="mk">§</span> 질문 → 기억 검색(의미 + 키워드) → 근거와 함께 답 · 저장된
        기억에서만
      </div>
    </section>
  )
}
