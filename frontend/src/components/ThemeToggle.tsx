import { useTheme, type Theme } from '../hooks/useTheme'

const OPTIONS: { value: Theme; label: string; aria: string }[] = [
  { value: 'system', label: '시스템', aria: '시스템 테마' },
  { value: 'light', label: '라이트', aria: '라이트 테마' },
  { value: 'dark', label: '다크', aria: '다크 테마' },
]

/** 시스템/라이트/다크 3단 테마 전환. 설정 화면에 배치(사이드바에서 이동). */
export function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  return (
    <div className="typesel" role="group" aria-label="테마 선택">
      {OPTIONS.map((opt) => (
        <button
          key={opt.value}
          type="button"
          className={opt.value === theme ? 'tsel on' : 'tsel'}
          aria-pressed={opt.value === theme}
          aria-label={opt.aria}
          title={opt.aria}
          onClick={() => setTheme(opt.value)}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}
