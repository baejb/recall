# 훅 가이드

두 종류의 훅이 있다. **Claude Code 훅**(편집 시 자동 포맷)과 **git 훅**(커밋 강제 검사).

## 사전 준비 (각 개발자 1회)

```bash
bash scripts/setup-hooks.sh   # git config core.hooksPath=.githooks
```

> 훅 스크립트는 **bash** 로 실행된다. Windows 에서는 Git Bash(또는 WSL)가 필요하다.
> `*.sh` 는 LF 개행이어야 한다(`.gitattributes` 로 강제).

## 1. Claude Code 훅 — 편집 시 자동 포맷

- 등록: `.claude/settings.json` 의 `PostToolUse`(matcher `Edit|Write|MultiEdit`).
- 스크립트: `.claude/hooks/format-on-edit.sh`. 편집된 파일 경로를 받아 확장자별로 포맷한다.
  - **프론트**(`ts/tsx/js/jsx/json/css`): `frontend/node_modules/.bin/prettier --write`
    (prettier 가 설치돼 있을 때만 — `cd frontend && npm install` 필요).
  - **Java**: gradle spotless 는 편집마다 돌리기엔 느려 **여기선 건너뛴다**. 대신 커밋 시
    pre-commit 이 `spotlessCheck` 로 잡는다.
- 조용히 실패하지 않도록 포맷 실패는 무시하고 편집 자체는 막지 않는다(포맷은 보조).

## 2. git 훅 — 커밋 강제 검사 (`.githooks/pre-commit`)

스테이지된 파일에 대해 검사하고, 하나라도 걸리면 **커밋을 막는다**.

1. **비밀/환경 파일 차단**: `.env`·`.env.*`, `*.p12`, `*.jks`, `*.pem`, `*.key` 는 커밋 불가.
   (단 `.env.example`·`.env.sample`·`.env.template` 템플릿은 허용 — 비밀이 없으므로.)
2. **디버그 출력 차단**: 프론트 `console.log/debug`, 백엔드 `System.out/err.print`.
3. **포맷 검사**:
   - 프론트 스테이지 파일 → `prettier --check`. 불일치 시 `cd frontend && npm run format`.
   - Java 스테이지 파일 → `cd backend && ./gradlew spotlessCheck`. 불일치 시 `./gradlew spotlessApply`.

### 통과 못 할 때

메시지가 지시하는 명령으로 고친 뒤 다시 `git add` → `git commit`. **`--no-verify` 로 우회하지
않는다.** 훅이 자꾸 틀리면 훅을 고쳐 PR 한다(우회는 규칙 회피).

## 포맷터 수동 실행

```bash
cd frontend && npm run format         # prettier --write .
cd frontend && npm run format:check   # 검사만

cd backend && ./gradlew spotlessApply  # 자동 정리
cd backend && ./gradlew spotlessCheck  # 검사만
```

## 왜 이렇게 하나

- 포맷을 **편집 시 자동 + 커밋 시 검사** 2단계로 두어, 리뷰에서 스타일 논쟁을 없앤다.
- 비밀·디버그 출력 차단은 불변 원칙("민감정보 유출 금지", "조용한 실패 금지")의 기계적 방어선.
