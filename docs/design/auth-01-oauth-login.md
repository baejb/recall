# 인증 실배선 — Google OAuth 로그인 · 이메일 허용목록 · CSRF 복구

> 상태: 완료(로컬 검증) · 기준: `docs/architecture.md`, `backend/CLAUDE.md`(멀티유저·시큐어코딩)

## 왜

소유자 격리는 이미 다 되어 있었다 — 모든 읽기·쓰기가 `user_id` 로 스코프되고, 교차유출 릴리스 게이트
테스트까지 있었다. **그런데 입구가 열려 있었다.**

- `SecurityConfig` 는 `csrf.disable()` + `anyRequest().permitAll()`
- `BootstrapCurrentUserProvider` 가 **항상 `app_user.id=1`** 을 반환
- 프론트에는 로그인 화면도, 401 처리도 없었다

즉 격리 코드와 그 테스트가 지키는 대상이 아무도 아니었다. 이 슬라이스는 그 입구를 만든다.

## 결정 4개 (팀)

| # | 결정 | 대안과 이유 |
|---|---|---|
| 담당 | 백엔드 + 프론트 전부 | 코드 주석은 "후속(팀원)" 이었지만 팀 확인 후 진행 |
| 가입 정책 | **허용 이메일 화이트리스트**(env) | "첫 로그인 1명 자동 등록"은 배포 후 남이 먼저 들어오면 계정을 뺏긴다 |
| 기존 데이터 | **새 사용자로 시작**(id=2부터), 부트스트랩 데이터 보존 | "첫 사용자가 id=1 을 이어받는" 안은 먼저 로그인한 사람이 남의 기억을 얻는 경로가 된다 |
| 인증 방식 | **세션 기반 `oauth2Login`** | JWT 는 토큰 보관(XSS)·갱신·SSE 헤더 전달을 직접 만들어야 한다. 셀프호스트 SPA + 동일 오리진이면 세션이 단순하다 |

## 무엇을

### 스위치는 프로필 하나 (`oauth`)

`SPRING_PROFILES_ACTIVE=oauth` 가 **로그인 · 소유자 해석 · CSRF 를 함께** 켠다.

- `SecurityConfig` 의 두 체인이 `@Profile("oauth")` / `@Profile("!oauth")` 로 갈린다
- `SecurityContextCurrentUserProvider`(oauth) ↔ `BootstrapCurrentUserProvider`(!oauth)
- `application.yml` 의 `registration.google` 블록도 그 프로필 문서 안에 있다

**왜 하나인가** — 전에는 `recall.auth.provider` 속성이 소유자 해석을 갈랐다. 프로필과 속성이 따로면
"로그인은 켜졌는데 소유자 해석은 부트스트랩(항상 id=1)" 조합이 만들어질 수 있고, 그건 **로그인한
사용자가 남의 데이터를 보는** 상태이면서 화면상으로는 정상으로 보인다(🔴 교차유출). 스위치가 하나면 그
조합이 존재하지 않는다.

**왜 프로필 문서 안에 registration 을 두나** — registration 속성이 있는데 `client-id` 가 비면
`ClientRegistrationRepository` 생성이 실패해 부팅이 막힌다(그래서 원래 주석 처리돼 있었다). 프로필
안에 두면 부트스트랩 모드에서 아예 로드되지 않아, 로컬 개발·테스트가 Google 설정 없이 그대로 돈다.

### auth 모듈 (`app_user` 테이블 소유자)

| 클래스 | 역할 |
|---|---|
| `service/entity/AppUser` | `app_user` 엔티티. 식별은 `(provider, subject)` — 이메일이 아니다 |
| `service/AppUserProvisioning` | **허용목록 판정 + 계정 조회/생성이 같은 자리** |
| `service/RecallOidcUserService` | OIDC 관문 — 세션 생성 **전에** 거절하고, 통과하면 id 를 principal 에 싣는다 |
| `AppUserPrincipal` | `DefaultOidcUser` + `app_user.id` |
| `config/SecurityContextCurrentUserProvider` | principal → 소유자 id. 없으면 **401 을 던진다** |
| `config/OAuthModeChecks` | 허용목록 비면 부팅 실패, 부트스트랩 데이터 있으면 경고 |
| `controller/AuthController` | `GET /api/me`, `POST /api/auth/logout` |

### 프론트

- `LoginPage` — 라우트가 아니라 **앱 루트의 분기**다. 미로그인 상태에서 `/memories` 로 들어와도 그
  화면을 잠깐도 그리지 않는다(빈 목록이 "기억이 없다"로 보인다).
- `useSession` — 부팅 시 `/api/me` 한 번. 이후 어느 호출에서든 401 이 나면 요청 창구가 던지는
  `recall:unauthenticated` 이벤트를 받아 익명으로 되돌린다.
- `client.ts` — 상태변경 요청에 `X-XSRF-TOKEN` 헤더를 붙인다(SSE 포함). 401 은 이벤트로 알린다.
- `Sidebar` — **목업 사용자(`이혜린 · hrlee@proten.co.kr`)가 하드코딩돼 있던 것을** 실제 세션 정보로
  교체하고 로그아웃 버튼을 붙였다.

## 설계 판단

- **식별자는 `(provider, subject)`, 이메일이 아니다** — 이메일은 provider 쪽에서 바뀐다(계정 이관·별칭).
  이메일을 키로 쓰면 같은 사람이 다른 사용자가 되거나, 이메일을 넘겨받은 사람이 남의 데이터에 붙는다.
  이메일은 표시·허용목록 판정용으로만 저장하고 매 로그인마다 갱신한다.
- **거절이 세션 생성보다 앞** — 허용목록 판정을 `OidcUserService` 안에서 한다. 뒤에서 인가로 막으면
  허용되지 않은 계정도 **로그인 세션은 갖게** 된다.
- **`email_verified` 를 요구한다** — 검증되지 않은 이메일을 허용목록과 비교하면 provider 에서 남의
  이메일을 주장하는 계정이 통과할 수 있다(허용목록 우회).
- **소유자 해석은 실패해야 할 때 실패한다** — principal 이 없거나 타입이 다르면 기본값으로 넘어가지 않고
  401 을 던진다. 그 자리에서 `1` 로 넘어가면 인증 없이 부트스트랩 사용자의 데이터에 닿는다.
- **모듈이 자기 배선을 소유한다** — oauth 필터 체인은 `auth/config/OAuthSecurityConfig` 에 있다.
  `common` 에 두면 `common` 이 `auth` 의 내부 구현(OIDC 사용자 서비스·로그인 예외·인증 실패 핸들러)을
  직접 import 해야 하고, 그건 "모듈 root 에는 공개 계약만" 규칙을 배선상 불가피하다는 이유로 넘는 것이다.
  부팅 검사가 필요한 건수도 각 모듈의 공개 계약(`MemoryAccess`·`CaptureAccess` 의 `countOwnedBy`)에
  묻는다 — 전에는 raw SQL 로 남의 테이블을 읽어서, 컬럼명 변경이 컴파일 신호 없이 남의 부팅을 깼다.
- **principal 에 id 를 싣는다** — 요청 하나에 `currentUserId()` 가 여러 번 불린다(목록·상세·설정·검색이
  각자 부른다). 매번 `(provider, subject)` 로 조회하면 같은 SELECT 가 반복된다.
- **API 실패는 리다이렉트가 아니라 401/403 JSON** — Spring 기본값은 로그인 페이지로 302 이고, SPA 의
  `fetch` 는 그걸 따라가 **로그인 HTML 을 200 으로** 받는다. 호출부는 "성공했는데 JSON 이 아니다"라는
  정체불명의 파싱 실패를 본다.
- **CSRF 는 `CsrfTokenRequestAttributeHandler`** — 기본 핸들러는 BREACH 방어로 토큰을 요청마다 다르게
  인코딩해 쿠키 값과 헤더 값이 어긋난다. SPA 가 쿠키를 읽어 헤더로 보내는 방식에서는 이 핸들러여야 맞는다.
  쿠키 발급 장치는 **`setCsrfRequestAttributeName(null)` 하나**다 — 지연 로딩을 꺼서 `CsrfFilter` 가 매
  요청 토큰을 로드·저장한다. 처음에는 `/api/me` 가 `CsrfToken` 을 파라미터로 받아 같은 일을 하려 했는데,
  같은 목적의 장치가 두 곳에 있으면서 서로를 몰랐다(미사용 파라미터라 먼저 지워질 쪽이었고, 둘 다 지워지면
  첫 상태변경 POST 가 403 이 된다). 장치를 설정 쪽으로 모으고 `OAuthSecurityChainTest` 가 고정한다.
- **403 이 실제로 나가는 경로는 CSRF 불일치뿐이다** — 허용목록 밖 계정은 403 이 아니다.
  `LoginNotAllowedException` 이 `AuthenticationException` 이라 OAuth 실패 경로를 타고
  `/?login_error=not_allowed` 로 **302** 된다. 이 문서·`ErrorCode.FORBIDDEN` javadoc·`client.ts` 가
  한동안 "403 = 허용목록 밖"이라고 적고 있었는데 그 계약은 코드에 없었다 — 믿고 분기를 짜면 죽은 코드가 된다.
- **`accessDeniedHandler` 는 스코프를 직접 잡는다** — 401 진입점은 스프링이 경로별로 받아 주지만
  (`defaultAuthenticationEntryPointFor`) 403 핸들러는 경로를 받지 않아 전역이다. 그대로 두면 브라우저로
  직접 여는 경로의 403 에서 화면 대신 JSON 이 노출된다.
- **허용목록이 비면 부팅 실패** — 비면 모든 로그인이 거절돼 "아무도 못 들어오는 인스턴스"가 정상 부팅한다.
  설정을 잊은 것과 의도적으로 아무도 허용하지 않은 것을 구분할 방법이 없어 부팅에서 막았다. 검사는
  `ApplicationRunner` 가 아니라 `InitializingBean` 이다 — 전자는 포트를 연 **뒤에** 돌아서 "부팅 실패"가
  아니라 "부팅 후 종료"가 되고 그사이 요청을 받는다.
- **🔴 부트스트랩 모드는 명시적 opt-in 을 요구한다** — 프로필이 빠지면 permitAll + CSRF off + 전 요청
  `id=1` 인데 **부팅은 성공**했다. 아무나 들어오는 오설정은 통과하고 아무도 못 들어오는 오설정은 차단하는,
  기준이 반대로 걸린 상태였다. 이제 `RECALL_BOOTSTRAP_MODE=true` 가 없으면 부팅을 막고, 그 값은 **일부러
  `.env.example` 에 두지 않는다**(복사해서 배포하면 무인증이 따라와 게이트가 의미를 잃는다). 로컬은
  `build.gradle` 의 `bootRun`·`test` 가 준다.
- **허용목록을 요청마다 다시 본다** — 판정이 로그인 시점 한 번뿐이면 목록에서 이메일을 빼도 그 브라우저는
  계속 통하고, 끊는 방법이 재시작뿐이다. 지금은 인메모리 세션이 그 문제를 가리지만 JDBC 세션으로 옮기면
  완화가 사라진다 — 세션 영속화와 요청 시점 재검사는 같이 가야 하므로 영속화보다 먼저 넣었다.
- **동시 첫 로그인을 견딘다** — 조회 후 생성(check-then-act)이라 콜백이 겹치면 하나가 유니크 제약에 걸린다.
  그 예외는 `AuthenticationException` 이 아니어서 실패 핸들러를 타지 못하고 흰 500 페이지가 된다. 재조회로
  받아내고, 그 밖의 비인증 예외(DB 장애 등)는 `InternalAuthenticationServiceException` 으로 감싼다.
- **로그인 성공 후 목적지를 잃지 않는다** — `defaultSuccessUrl("/", false)`. `alwaysUse=true` 는
  `SavedRequest` 를 버려서, 공유 링크(`/memories/42`)로 들어온 사람이 로그인 후 매번 `/` 로 간다.
- **`serialVersionUID` 를 고정한다** — 없으면 `AppUserPrincipal` 에 필드를 추가하는 순간 값이 바뀌고,
  JDBC 세션에서는 살아 있는 세션이 전부 역직렬화에 실패한다(배포 직후 전원 로그아웃 또는 500).
- **부트스트랩 모드를 숨기지 않는다** — 부팅 WARN + `/api/me` 의 `bootstrapMode` + 사이드바 경고 칩.
  이 모드가 배포로 새어 나가면 인스턴스가 열린 상태이고, 조용하면 아무도 모른다.
- **Flyway 변경 없음** — `app_user`(V9)·부트스트랩 시드(V11)가 이미 있고 컬럼도 그대로 쓴다.

## 검증

- 백엔드 단위테스트 **324개 통과**(신규 9개). 🔴 release-gate: 허용목록 밖 거절 시 계정 미생성 ·
  빈 허용목록은 전원 거절 · principal 없으면 401(기본 사용자로 넘어가지 않음).
- **부팅 검증(로컬)**:
  - 부트스트랩 모드 — `/api/me` → `{"userId":1,"bootstrapMode":true}`, 기존 화면 그대로 동작
  - oauth 프로필 + 빈 허용목록 → **부팅 실패**(의도한 fail-loud, 메시지에 설정 방법 포함)
  - oauth 프로필 + 허용목록 → 부팅 성공, `/api/health` 200(공개) · `/api/me` **401 봉투** ·
    `/api/memories` 401 · `/oauth2/authorization/google` → Google 로 **302**
- 프론트 `npm run build`(tsc 포함) · `lint` · `format:check` 통과.

**실제 Google 로그인 왕복은 검증하지 못했다** — 실 client-id/secret 이 필요하다(더미 값으로 리다이렉트
생성까지만 확인). 콘솔에서 OAuth 클라이언트를 만들고 승인된 리디렉션 URI 에
`http://localhost:8080/login/oauth2/code/google` 를 넣은 뒤 한 번 돌려봐야 한다.

## 범위 밖 / 후속

- **세션 저장소** — 지금은 인메모리 세션이라 재시작하면 전원 로그아웃된다. 셀프호스트 단일 인스턴스면
  받아들일 수 있고, 재시작이 잦거나 다중 인스턴스가 되면 Redis·JDBC 세션이 필요하다.
- **사용자 추가 UX** — 허용목록이 env 라서 사용자 추가는 재기동을 요구한다. 관리 화면이나 DB 기반
  허용목록은 별개 작업이다.
- **provider 확장** — 지금은 Google 만. `(provider, subject)` 키와 `OidcUserService` 구조는 그대로
  쓰지만, GitHub 처럼 OIDC 가 아닌 provider 는 `OAuth2UserService` 쪽 배선이 따로 필요하다.
- **감사 로그** — 로그인 성공·거절은 지금 애플리케이션 로그로만 남는다(테이블 없음).
- **부트스트랩 데이터 이관** — 결정에 따라 하지 않는다. 나중에 필요하면 `user_id` 를 옮기는 마이그레이션이
  필요하고, 그건 "누구에게 줄 것인가"를 사람이 정해야 하는 작업이다.
