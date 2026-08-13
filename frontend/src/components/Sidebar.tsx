import { NavLink } from 'react-router-dom'
import { useRecall } from '../hooks/useRecall'
import { ThemeToggle } from './ThemeToggle'

function navClass({ isActive }: { isActive: boolean }): string {
  return isActive ? 'nav active' : 'nav'
}

/** 좌측 내비게이션. 핵심 흐름(물어보기·붙여넣기·검토함·내 기억)만 노출한다. */
export function Sidebar() {
  const { reviewCount } = useRecall()
  return (
    <aside className="side">
      <div className="brand">
        <div className="logo">🧠</div>
        <div>
          <div className="name">Recall</div>
          <div className="sub">내가 물어보는 곳</div>
        </div>
      </div>

      <div className="navlabel">쓰기</div>
      <NavLink to="/" end className={navClass}>
        <span className="ico">🔎</span> 물어보기
      </NavLink>
      <NavLink to="/capture" className={navClass}>
        <span className="ico">➕</span> 새로 붙여넣기
      </NavLink>
      <NavLink to="/reviews" className={navClass}>
        <span className="ico">📥</span> 검토함
        {reviewCount > 0 && <span className="badge">{reviewCount}</span>}
      </NavLink>

      <div className="navlabel">보기</div>
      <NavLink to="/memories" className={navClass}>
        <span className="ico">🧠</span> 내 기억
      </NavLink>

      <div className="navlabel">설정</div>
      <NavLink to="/settings" className={navClass}>
        <span className="ico">⚙️</span> 설정
      </NavLink>

      <div className="spacer" />
      <ThemeToggle />
      <div className="userchip">
        <div className="av">이</div>
        <div>
          <div className="who">이혜린 · 신입 개발자</div>
          <div className="mail">hrlee@proten.co.kr</div>
        </div>
      </div>
    </aside>
  )
}
