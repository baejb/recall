/**
 * 로그인 화면 — 세션이 없을 때 앱 대신 이것만 보여준다.
 *
 * 라우트가 아니라 앱 루트의 분기다: 로그인하지 않은 상태에서 `/memories` 같은 주소로 들어오면 그 화면을
 * 잠깐이라도 그리면 안 된다(빈 목록이 "기억이 없다"로 보인다). 그래서 라우터보다 앞에서 갈라낸다.
 *
 * 로그인은 백엔드가 시작한다(`/oauth2/authorization/google`) — SPA 가 아니라 서버가 리다이렉트를
 * 주도해야 세션 쿠키가 제대로 붙는다. 그래서 `fetch` 가 아니라 주소 이동이다.
 */
export function LoginPage({ error }: { error?: 'not_allowed' | 'failed' }) {
  return (
    <div className="loginwrap">
      <section className="card pad login">
        <div className="eyebrow">Recall</div>
        <h1 className="h1">근거와 함께 회상</h1>
        <p className="lede">
          이 인스턴스는 <b>허용된 계정만</b> 사용할 수 있어요. 구글 계정으로 로그인하세요.
        </p>

        {error === 'not_allowed' && (
          <div className="note bad" role="alert">
            <b>허용되지 않은 계정</b>
            <span>
              이 인스턴스에 등록된 계정이 아니에요. 관리자에게 계정 추가를 요청하세요(서버의 허용
              이메일 목록에 추가해야 합니다).
            </span>
          </div>
        )}
        {error === 'failed' && (
          <div className="note bad" role="alert">
            <b>로그인에 실패했어요</b>
            <span>잠시 후 다시 시도해 주세요. 계속 안 되면 서버 로그를 확인해야 합니다.</span>
          </div>
        )}

        <a className="btn primary loginbtn" href="/oauth2/authorization/google">
          구글 계정으로 로그인
        </a>
      </section>
    </div>
  )
}
