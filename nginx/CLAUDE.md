# nginx/CLAUDE.md — 리버스 프록시 규칙

> 루트 `../CLAUDE.md`와 함께 적용된다. 여기선 리버스 프록시 영역만 좁힌다.
> 이 문서는 **시작 셋팅 기준**이며, 배포 세부(TLS·도메인·서비스화)는 추후 확정한다.

## 역할

nginx는 **호스트에서** 구동한다(개발용 Docker Compose는 DB만 띄운다). 두 가지를 한다.

1. 빌드된 프론트 SPA(`frontend/dist`) **정적 서빙** + SPA fallback(`try_files … /index.html`).
2. **백엔드 경로 프록시**로 프론트/백엔드를 **동일 오리진**으로 묶는다(브라우저 CORS 제거).
   `/api` 외에 **OAuth 경로 두 개**가 반드시 포함된다 — 아래 필수 규칙 참조.

> 기준 설정 파일은 `nginx/nginx.conf`. 배포 시 `root` 를 프론트 빌드 산출물(`frontend/dist`)로
> 맞춘다.

## 필수 규칙

- **SSE 프록시**: 조회(`POST /api/query`)는 서버-전송 이벤트 스트리밍이다. `/api` location에
  **`proxy_buffering off;`** 를 반드시 유지한다. 버퍼링이 켜지면 스트리밍 답변이 끊긴다
  (불변 원칙 "조용한 실패 금지"와 직결). `proxy_http_version 1.1;` + `Connection ''` 도 함께 둔다.
- **🔴 백엔드로 보낼 경로는 셋이다**: `/api/` · `/oauth2/` · `/login/`. 뒤의 둘이 빠지면 SPA fallback
  (`try_files $uri /index.html`)이 로그인 시작 주소와 **Google 콜백의 authorization code** 를 SPA HTML 로
  삼킨다. 백엔드에 닿지 않으므로 세션이 만들어지지 않고, 증상은 "로그인했는데 로그인 화면"이다 — 콘솔·
  네트워크 탭에 에러가 없어 원인이 드러나지 않는다(조용한 실패). 백엔드 `PUBLIC_PATHS` 에 이 경로를 연
  것은 **백엔드 필터 체인 안에서만** 유효하다. dev 는 `frontend/vite.config.ts` 의 `API_PATHS`·
  `OAUTH_PATHS` 가 같은 목록을 갖는다 — 한쪽만 고치지 않는다.
- **🔴 OAuth 경로는 원래 `Host` 를 그대로 넘긴다**: 스프링이 요청의 Host 로 `redirect_uri` 를 만든다.
  nginx 는 `proxy_set_header Host $host`(+`X-Forwarded-*`), dev 의 vite 는 **`changeOrigin: false`** 다.
  바꿔 보내면 `redirect_uri` 가 프록시 대상 주소가 된다 — dev 에서 실제로 겪었다: `changeOrigin: true`
  면 `redirect_uri=http://localhost:8080/...` 이 되어 Google 이 브라우저를 백엔드 포트로 되돌리고,
  콜백은 처리되지만(세션 쿠키는 포트를 무시해 살아 있다) 로그인 성공 후 `/` 가 SPA 없는 :8080 이라
  **빈 화면**에 떨어진다("로그인은 됐는데 아무것도 안 보인다"). 배포에서는 어긋난 `redirect_uri` 를
  Google 이 `redirect_uri_mismatch` 로 거절한다.
- **API 호스트 하드코딩 금지**: 프론트는 상대경로 `/api`로만 호출한다. 백엔드 주소 변경은 nginx
  `proxy_pass` 한 곳에서 흡수한다.
- **비밀 금지**: 설정 파일에 키·토큰을 넣지 않는다.

## 배포 (추후)

TLS(인증서)·서버 도메인·정적 산출물 배치 경로(`root`)·프로세스 관리는 배포 단계에서 확정한다.
셀프호스트 단일 사용자 기준이라 인증/멀티테넌시는 도입하지 않는다.
