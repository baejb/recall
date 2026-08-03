---
name: test
description: >-
  Recall 에서 테스트를 실행·작성할 때 사용한다. 백엔드(Gradle/JUnit)와 프론트
  (타입체크/빌드) 실행 명령, 단일 테스트 실행법, 그리고 무엇을 테스트해야 하는지
  (불변 원칙 방어 + PRD 단계별 Eval)를 안내한다. "테스트 돌려줘", "테스트 짜줘",
  "이 변경 검증해줘" 요청 시 이 스킬을 따른다.
---

# test — 테스트 실행 / 작성

## 1. 실행 명령

**백엔드** (`backend/`, JUnit Platform):

```bash
cd backend
./gradlew test                                    # 전체
./gradlew test --tests 'com.recall.SomeTest'      # 단일 클래스
./gradlew test --tests 'com.recall.SomeTest.method'  # 단일 메서드
./gradlew build                                   # 컴파일 + 테스트 + spotlessCheck
```

**프론트** (`frontend/`): 아직 단위 테스트 러너 미도입. 지금의 검증 게이트는 타입체크/빌드다.

```bash
cd frontend
npm run build          # tsc --noEmit(타입체크) → vite build
npm run format:check   # 포맷 검사
```

> 프론트 테스트 러너(vitest 등)를 도입한다면 근거를 남기고 package.json 에 `test` 스크립트를
> 추가한 뒤 이 문서를 갱신한다.

## 2. 무엇을 테스트하나 — 불변 원칙 방어 (우선순위 최상)

기능 테스트보다 먼저, 아래 🔴 치명 회귀를 케이스로 고정한다.

- **마스킹 선행**: 원문이 외부로 나가기 전에 비밀 값이 마스킹되는가(패턴 매칭).
- **승인 게이트**: 저장/추출 경로가 승인 전 memory 에 쓰지 않는가.
- **근거 없는 생성 금지**: 후보가 없을 때 답변이 지어내지 않고 "기록 없음"인가.
- **충돌 자동 덮어쓰기 금지**: 모순 판정이 기존 기록을 덮어쓰지 않는가.

## 3. 무엇을 테스트하나 — PRD 단계별 Eval

단계 성격에 따라 방식이 다르다(§ recall-feature 스킬과 연동).

- **🟢 결정적 단계**(플래너·검색·가중치): **순수함수 테스트**. 같은 입력 = 같은 전략/결과,
  미정의 조합은 폴백 + 로그. 라벨→기대 plan 일치.
- **🔵 LLM 단계**(분류·추출·판정·리랭크·답변): 라벨셋 지표(Recall@k·nDCG) 또는
  LLM-as-a-Judge(근거 일치·형식·faithfulness). 임계값·가중치는 라벨셋에서 fit(직관 금지).

## 4. 작성 관례

- 백엔드 테스트는 `backend/src/test/java/com/recall/…`. Spring Boot 4 slice 스타터(`*-test`) 활용.
- DB 가 필요한 테스트는 pgvector 를 전제로 한다(단일 사용자 스키마 — `user_id` 없음).
- 테스트가 실패하면 **그대로 보고**한다(통과한 척 금지 — 불변 원칙 "조용한 실패 금지").
- 변경을 커밋하기 전 관련 테스트를 돌리고, 실패 로그를 근거로 고친다.
