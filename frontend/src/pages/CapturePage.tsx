import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { useToast } from '../hooks/useToast'
import { detectSecrets, maskText, type DetectedSecret } from '../lib/masking'
import { Stepper } from '../components/Stepper'
import { StepProgress } from '../components/StepProgress'

const EXTRACT_STEPS = ['유형 후보 추출', '문제·시도·해결 뽑기', '검토함에 후보 올리기'] as const

const SAMPLE_TEXT = `[나] docker compose up 하면 컨테이너가 볼륨에 못 써. Permission denied 남.
    포스트그레 컨테이너가 /var/lib/postgresql/data 에 EACCES...
    참고로 내 .env 에 DB_PASSWORD=s3cr3t!pw 랑 AWS_KEY=AKIA5XXQ 있음
[클로드] 호스트 볼륨 소유자 UID와 컨테이너 유저 UID가 안 맞아서 그래요.
    1) chmod 777 은 임시방편이고 보안상 별로…
    2) Dockerfile에서 USER 지정하고, 마운트 경로를 chown 하는 게 정석입니다.
[나] 오 chown 하니까 됐다!`

interface Draft {
  text: string
  found: DetectedSecret[]
  masked: string
}

type Step = 'input' | 'mask' | 'extracting'

export function CapturePage() {
  const [step, setStep] = useState<Step>('input')
  const [text, setText] = useState(SAMPLE_TEXT)
  const [draft, setDraft] = useState<Draft | null>(null)
  const { submitCapture } = useRecall()
  const navigate = useNavigate()
  const toast = useToast()

  const goMask = () => {
    const found = detectSecrets(text)
    setDraft({ text, found, masked: maskText(text, found) })
    setStep('mask')
  }

  // StepProgress 애니메이션이 끝나면 실제로 서버에 저장한다. 서버가 마스킹·추출을 수행.
  const finishExtract = async () => {
    if (!draft) return
    try {
      await submitCapture(draft.text)
      toast('✓ 검토함에 올렸어요')
      navigate('/reviews')
    } catch (e) {
      // 조용한 실패 금지: 실패를 알리고 입력 화면으로 되돌린다.
      toast(`⚠️ 저장 실패: ${e instanceof Error ? e.message : '알 수 없는 오류'}`)
      setStep('input')
    }
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
            비동기라 이 화면을 떠나도 돼요.
          </p>
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
                <span className="lock">🔒</span>
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
        <textarea rows={9} value={text} onChange={(e) => setText(e.target.value)} />
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
