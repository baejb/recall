import { useEffect, useState } from 'react'

export type Theme = 'system' | 'light' | 'dark'

const STORAGE_KEY = 'recall-theme'

function readInitialTheme(): Theme {
  const saved = localStorage.getItem(STORAGE_KEY)
  return saved === 'light' || saved === 'dark' ? saved : 'system'
}

/** 라이트/다크/시스템 3단 테마 상태. 선택값은 localStorage에 저장하고
 * `document.documentElement`의 `data-theme`에 반영해 index.css의 CSS 변수를 전환한다. */
export function useTheme(): { theme: Theme; setTheme: (theme: Theme) => void } {
  const [theme, setTheme] = useState<Theme>(readInitialTheme)

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, theme)
    if (theme === 'system') {
      delete document.documentElement.dataset.theme
    } else {
      document.documentElement.dataset.theme = theme
    }
  }, [theme])

  return { theme, setTheme }
}
