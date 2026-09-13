import type { Judgement } from '../types'

/**
 * S4 판정의 표시 정보.
 *
 * `Record<Judgement, …>` 로 두는 이유: 판정 값이 늘면 여기서 컴파일이 깨진다. 기본값(`?? 신규`)으로
 * 흘리면 새 판정이 조용히 "신규"로 표시되고, 그게 정확히 이 화면이 고치려는 결함이다.
 *
 * `tone` 은 `index.css` 의 `.pill.ok|warn|bad` 에 대응한다. 충돌만 `bad` — 기존 기억과 모순된다는
 * 뜻이라 다른 판정과 같은 무게로 보이면 안 된다.
 */
export const JUDGEMENT_META: Record<
  Judgement,
  { label: string; tone: 'ok' | 'warn' | 'bad'; hint: string }
> = {
  NEW: {
    label: '신규',
    tone: 'ok',
    hint: '기존 기억과 겹치지 않는 새 내용이에요.',
  },
  RECURRENCE: {
    label: '재발',
    tone: 'warn',
    hint: '전에 겪은 문제가 다시 났어요. 아래 기존 기억과 함께 보세요.',
  },
  SUPPLEMENT: {
    label: '보완',
    tone: 'warn',
    hint: '기존 기억에 덧붙는 내용이에요. 아래 기존 기억과 함께 보세요.',
  },
  CONFLICT: {
    label: '충돌',
    tone: 'bad',
    hint: '기존 기억과 어긋나요. 자동으로 덮어쓰지 않았으니, 어느 쪽이 맞는지 직접 확인하고 정하세요.',
  },
  UNKNOWN: {
    label: '판정 불명',
    tone: 'bad',
    hint: '서버가 이 화면이 모르는 판정을 보냈어요. 기존 기억과의 관계를 확인할 수 없으니 승인 전에 원본을 직접 보세요.',
  },
}
