import { NavLink } from 'react-router-dom'
import type { MeResponse } from '../api/dto'
import { useRecall } from '../hooks/useRecall'

function navClass({ isActive }: { isActive: boolean }): string {
  return isActive ? 'nav active' : 'nav'
}

/** 접힌 노트 모서리 + R 로고 마크. teal/blue 액센트는 토큰을 따라간다(테마 대응). */
function LogoMark() {
  return (
    <svg className="logo" viewBox="0 0 32 32" role="img" aria-label="Recall">
      <path
        d="M7 1 H22.5 L31 9.5 V25 A6 6 0 0 1 25 31 H7 A6 6 0 0 1 1 25 V7 A6 6 0 0 1 7 1 Z"
        fill="var(--accent)"
      />
      <path d="M22.5 1 L31 9.5 H22.5 Z" fill="var(--accent-ink)" />
      <g fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
        <path d="M11 9 V23.5" />
        <path d="M11 9 H16 A3.6 3.6 0 0 1 16 16 H11" />
        <path d="M13.6 16 L18.6 23.5" />
      </g>
    </svg>
  )
}

/**
 * 좌측 내비게이션. 핵심 흐름(물어보기·붙여넣기·검토함·내 기억)만 노출한다. 아이콘은 얇은 단색 라인으로 통일.
 *
 * 하단 사용자 칩은 **실제 세션**을 보여준다(전에는 목업 이름·이메일이 하드코딩돼 있었다). 부트스트랩
 * 모드에서는 "인증 없음"을 그대로 표시한다 — 그 사실을 숨기면 열려 있는 인스턴스가 정상처럼 보인다.
 */
export function Sidebar({ me, onLogout }: { me: MeResponse; onLogout: () => void }) {
  const { reviewCount } = useRecall()
  return (
    <aside className="side">
      <div className="brand">
        <LogoMark />
        <div>
          <div className="name">Recall</div>
          <div className="sub">근거와 함께 회상</div>
        </div>
      </div>

      <div className="navlabel">쓰기</div>
      <NavLink to="/" end className={navClass}>
        <svg viewBox="0 0 24 24">
          <circle cx="11" cy="11" r="7" />
          <path d="M16 16l5 5" />
        </svg>
        물어보기
      </NavLink>
      <NavLink to="/capture" className={navClass}>
        <svg viewBox="0 0 24 24">
          <path d="M12 5v14M5 12h14" />
        </svg>
        새로 붙여넣기
      </NavLink>
      <NavLink to="/reviews" className={navClass}>
        <svg viewBox="0 0 24 24">
          <path d="M4 14v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4" />
          <path d="M8 10l4 4 4-4" />
          <path d="M12 3v11" />
        </svg>
        검토함
        {reviewCount > 0 && <span className="badge">{reviewCount}</span>}
      </NavLink>

      <div className="navlabel">보기</div>
      <NavLink to="/memories" className={navClass}>
        <svg viewBox="0 0 24 24">
          <path d="M4 5h16M4 12h16M4 19h10" />
        </svg>
        내 기억
      </NavLink>

      <div className="navlabel">설정</div>
      <NavLink to="/settings" className={navClass}>
        <svg viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="3" />
          <path d="M12 3v3M12 18v3M3 12h3M18 12h3" />
        </svg>
        설정
      </NavLink>

      <div className="spacer" />

      {me.bootstrapMode ? (
        <div className="userchip" title="로그인 없이 단일 사용자로 동작 중입니다">
          <div className="av warnav">!</div>
          <div className="userinfo">
            <div className="who">인증 없음 · 단일 사용자</div>
            <div className="mail">app_user #{me.userId}</div>
          </div>
        </div>
      ) : (
        <div className="userchip">
          <div className="av">{initial(me)}</div>
          <div className="userinfo">
            <div className="who">{me.displayName || me.email}</div>
            <div className="mail">{me.email}</div>
          </div>
          <button type="button" className="chipbtn" onClick={onLogout}>
            로그아웃
          </button>
        </div>
      )}
    </aside>
  )
}

/** 아바타에 넣을 한 글자 — 이름이 없으면 이메일 첫 글자. */
function initial(me: MeResponse): string {
  const source = me.displayName || me.email
  return source ? source.slice(0, 1).toUpperCase() : '?'
}
