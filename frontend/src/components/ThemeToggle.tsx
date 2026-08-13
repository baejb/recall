import { useTheme, type Theme } from '../hooks/useTheme'

const OPTIONS: { value: Theme; label: string; icon: string; aria: string }[] = [
  { value: 'system', label: '시스템', icon: '🖥️', aria: '시스템 테마' },
  { value: 'light', label: '라이트', icon: '☀️', aria: '라이트 테마' },
  { value: 'dark', label: '다크', icon: '🌙', aria: '다크 테마' },
]

/** 시스템/라이트/다크 3단 테마 전환. Sidebar 하단에 배치. */
export function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  return (
    <div className="typesel themetoggle" role="group" aria-label="테마 선택">
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
          <span aria-hidden="true">{opt.icon}</span> {opt.label}
        </button>
      ))}
    </div>
  )
}
