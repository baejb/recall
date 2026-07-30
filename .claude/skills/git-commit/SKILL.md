---
name: git-commit
description: >-
  Recall 저장소에서 변경사항을 커밋/PR 로 만들 때 사용한다. Conventional Commits
  형식과 영역 scope 를 강제하고, 기본 브랜치 직접 커밋을 막고(작업 브랜치→PR), 비밀
  파일 커밋을 차단하며, pre-commit 훅을 우회하지 않게 한다. 무엇보다 커밋 메시지에
  AI/Claude 협업 흔적(Co-Authored-By: Claude, Generated with Claude Code 등)을
  절대 넣지 않는다. "커밋해줘", "PR 만들어줘" 요청 시 이 스킬을 따른다.
---

# git-commit — 커밋 / PR 규칙

Recall 의 커밋은 **사람 작성물**로 남는다. 아래를 순서대로 지킨다.

## 0. 절대 규칙

- **커밋 메시지에 AI/Claude 협업 흔적을 넣지 않는다.** `Co-Authored-By: Claude …`,
  `🤖 Generated with Claude Code`, "Claude가 작성/생성" 류의 trailer·문구 전부 금지.
- **비밀을 커밋하지 않는다.** `.env*`, `*.p12/*.jks/*.pem/*.key`, 토큰·키. (pre-commit 이
  1차 방어하지만 스스로도 확인)
- **기본 브랜치에 직접 커밋하지 않는다.** 작업 브랜치에서 커밋 → PR.

## 1. 브랜치

- 현재 브랜치를 확인한다: `git branch --show-current`.
- 기본 브랜치(main 등)이면 작업 브랜치를 판다: `git checkout -b <type>/<주제>`
  (예: `feat/hybrid-retriever`, `fix/sse-reconnect`, `chore/…`, `docs/…`).

## 2. 스테이징 · 검토

- `git status` / `git diff` 로 의도한 변경만 담는다. 무관한 파일을 쓸어 담지 않는다.
- 생성물(`dist/`, `build/`, `node_modules/`)이 섞이지 않았는지 확인.

## 3. 커밋 메시지 (Conventional Commits)

```
<type>(<scope>): <요약, 한국어 가능>

<본문: 무엇을·왜. 되돌리기 어려운 선택은 근거를. PRD 근거 §도 인용 가능>
```

- type: `feat` `fix` `refactor` `docs` `chore` `test` `perf` `build`.
- scope: `frontend` `backend` `nginx` `infra` `prd` 등 영역.
- 예: `feat(backend): 하이브리드 검색 채널 RRF 융합 추가` / `fix(frontend): SSE 재연결 시 중복 카드 제거`.
- **trailer 에 AI 협업 표기를 붙이지 않는다**(§0).

## 4. 커밋 실행 · 훅

- `git commit` 시 `.githooks/pre-commit` 이 포맷·금지패턴·비밀을 검사한다.
- 실패하면 메시지가 지시하는 명령(`npm run format` / `./gradlew spotlessApply` 등)으로 고치고
  다시 커밋한다. **`--no-verify` 로 우회하지 않는다.**

## 5. PR

- 푸시: `git push -u origin <branch>`.
- PR 은 `gh pr create` 로 만든다. 제목은 커밋 요약과 같은 톤, 본문에 변경 요약·영향·확인 방법.
- **PR 본문에도 AI 생성 표기(🤖 Generated with … 등)를 넣지 않는다.**
- 사용자가 명시적으로 요청할 때만 push/PR 한다(요청 전엔 로컬 커밋까지만).
