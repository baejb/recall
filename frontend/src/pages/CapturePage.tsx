import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { useToast } from '../hooks/useToast'
import { detectSecrets, maskText, type DetectedSecret } from '../lib/masking'
import { Stepper } from '../components/Stepper'
import { StepProgress } from '../components/StepProgress'

const EXTRACT_STEPS = ['유형 후보 추출', '문제·시도·해결 뽑기', '검토함에 후보 올리기'] as const

interface Draft {
  text: string
  found: DetectedSecret[]
  masked: string
}

type Step = 'input' | 'mask' | 'extracting' | 'done'

export function CapturePage() {
  const [step, setStep] = useState<Step>('input')
  const [text, setText] = useState('')
  const [draft, setDraft] = useState<Draft | null>(null)
  // 검토함에 실제로 떴는지(true) vs 아직 정리 중(false=타임아웃). 완료 화면 문구를 가른다.
  const [readyInReview, setReadyInReview] = useState(false)
  const { submitCapture } = useRecall()
  const navigate = useNavigate()
  const toast = useToast()

  const goMask = () => {
    if (!text.trim()) {
      toast('붙여넣을 내용을 입력해 주세요')
      return
    }
    const found = detectSecrets(text)
    setDraft({ text, found, masked: maskText(text, found) })
    setStep('mask')
  }

  // StepProgress 애니메이션이 끝나면 실제로 서버에 저장한다. 서버가 마스킹·추출을 수행.
  // submitCapture 내부에서 검토함에 새 항목이 뜰 때까지 짧게 폴링한다(비동기 처리 → P2:
  // 즉시 이동하면 검토함이 비어 보이는 문제 방지). 폴링 중에도 이 화면의 "정리하는 중…"
  // 스피너가 계속 보이므로 빈 화면·무한 스피너는 아니다.
  const finishExtract = async () => {
    if (!draft) return
    try {
      const { found } = await submitCapture(draft.text)
      // found=false면 타임아웃(10s) — 캡처 자체는 성공했으니 실패로 보이면 안 된다.
      // 아직 검토함에 안 떴을 수 있음을 알리고, 조용히 넘어가지 않는다(조용한 실패 금지).
      toast(found ? '검토함에 올렸어요' : '정리가 조금 더 걸려요 — 곧 검토함에 나타나요')
      // 자동 이동 금지: 사용자가 다른 화면(물어보기 등)에서 작업 중일 수 있다. 완료 화면으로만
      // 전환하고, 실제 검토함 이동은 사용자가 버튼을 눌러 허락할 때만 한다(질문 입력 유실 방지).
      // 이미 이 화면을 떠났다면 setStep 은 no-op이고 위 토스트만 알림으로 남는다.
      setReadyInReview(found)
      setStep('done')
    } catch (e) {
      // 조용한 실패 금지: 실패를 알리고 입력 화면으로 되돌린다.
      toast(`저장 실패: ${e instanceof Error ? e.message : '알 수 없는 오류'}`)
      setStep('input')
    }
  }

  const restart = () => {
    setText('')
    setDraft(null)
    setReadyInReview(false)
    setStep('input')
  }

  if (step === 'extracting') {
    return (
      <section className="screen">
        <Stepper current={2} />
        <div className="card pad">
          <StepProgress
            steps={EXTRACT_STEPS}
            title="AI가 정리하는 중…"
            onComplete={() => void finishExtract()}
          />
          <p
            style={{ fontSize: 13, color: 'var(--text-faint)', marginTop: 20, textAlign: 'center' }}
          >
            비동기라 이 화면을 떠나도 돼요. 다른 화면으로 이동해도 저장은 계속돼요.
          </p>
        </div>
      </section>
    )
  }

  if (step === 'done') {
    return (
      <section className="screen">
        <div className="eyebrow">저장</div>
        <h1 className="h1">{readyInReview ? '검토함에 올렸어요' : '정리 중이에요'}</h1>
        <p className="lede">
          {readyInReview
            ? '승인해야 기억이 돼요. 지금 검토하거나, 계속 붙여넣어도 돼요.'
            : '정리가 조금 더 걸려요. 끝나면 검토함에 나타나요 — 계속 붙여넣어도 돼요.'}
        </p>
        <div className="card pad">
          <div className="row">
            <button className="btn primary" onClick={() => navigate('/reviews')}>
              검토함 보기 →
            </button>
            <button className="btn" onClick={restart}>
              새로 붙여넣기
            </button>
          </div>
          <div className="note">
            <b>승인 게이트</b>
            <span>붙여넣은 원문은 검토함을 거쳐 승인해야 기억이 돼요 — 자동 저장은 없어요.</span>
          </div>
        </div>
      </section>
    )
  }

  if (step === 'mask' && draft) {
    return (
      <section className="screen">
        <button className="backbtn" onClick={() => setStep('input')}>
          ← 붙여넣기로
        </button>
        <div className="eyebrow">저장</div>
        <h1 className="h1">비밀을 가렸어요 — 맞나요?</h1>
        <p className="lede">
          외부 LLM에 보내기 전에, 비밀번호·키로 보이는 값을 자동으로 찾아 가렸어요.
        </p>
        <Stepper current={1} />
        <div className="card pad">
          {draft.found.length > 0 ? (
            draft.found.map((f, i) => (
              <div className="maskitem" key={`${f.key}-${i}`}>
                <span className="lock">◈</span>
                <div className="info">
                  <span className="key">{f.key}</span>{' '}
                  <span className="prev">{f.val.slice(0, 10)}… → ●●●●●●●</span>
                </div>
                <span className="pill warn">가림</span>
              </div>
            ))
          ) : (
            <p style={{ color: 'var(--text-muted)', fontSize: 13.5 }}>
              감지된 비밀이 없어요. 그대로 진행합니다.
            </p>
          )}
          <p style={{ fontSize: 13, color: 'var(--text-muted)', margin: '12px 0 0' }}>
            이 값들은 <b>저장도, 전송도</b> 안 돼요. 원본에도 가려진 채로 보관돼요.
          </p>
          <hr className="divider" />
          <div className="row">
            <button className="btn primary" onClick={() => setStep('extracting')}>
              확인하고 정리 시작 →
            </button>
            <button className="btn" onClick={() => setStep('input')}>
              뒤로
            </button>
          </div>
          <div className="note">
            <b>설계</b>
            <span>마스킹은 룰로 먼저 잡고 사용자가 재확인 — 비밀 유출을 이중으로 막아요.</span>
          </div>
        </div>
      </section>
    )
  }

  return (
    <section className="screen">
      <div className="eyebrow">저장</div>
      <h1 className="h1">대화·에러를 그냥 붙여넣으세요</h1>
      <p className="lede">
        비밀은 저장 전 자동으로 가리고, AI가 핵심을 뽑아요. 유형·쪼개기는 검토에서 직접 정해요.
      </p>
      <Stepper current={0} />
      <div className="card pad">
        <label className="field-label">붙여넣기 (클로드·GPT 대화, 에러 로그 무엇이든)</label>
        <textarea
          rows={9}
          value={text}
          placeholder="클로드·GPT 대화나 에러 로그를 그대로 붙여넣으세요. 키·비밀번호·이메일은 저장 전에 자동으로 가려요."
          onChange={(e) => setText(e.target.value)}
        />
        <div className="between" style={{ marginTop: 16 }}>
          <span className="eyebrow">source: chat</span>
          <button className="btn primary" onClick={goMask}>
            다음: 마스킹 확인 →
          </button>
        </div>
      </div>
    </section>
  )
}
