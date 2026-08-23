# troubleshooting 프론트 렌더 — TS 카드 화면 + 목록 해결상태

> 상태: 완료 · 앞 슬라이스: [troubleshooting-01-type.md](troubleshooting-01-type.md)

## 왜

백엔드가 TS 카드를 만들고 `structured`로 내려보내는데 화면은 knowledge 렌더만 했다. 그래서

- 기억 상세는 TS 카드를 열어도 **"(내용 없음)"** 이었고(knowledge 평면 필드가 비어 있으니),
- 검토 상세의 TS 분기는 목업 4칸(문제/시도/해결/상태)만 그려서 에러 시그니처·환경·근본원인·시도별
  성공/실패가 화면에서 사라졌고,
- 기억 목록의 해결상태 배지는 adapter가 값을 `'미해결'`로 고정해 **해결된 것도 미해결로 보였다**
  (근거 없는 단정 — 불변 원칙 위반).

## 무엇을

### TS 카드 렌더 (`components/TroubleshootingCardView.tsx`)

`KnowledgeCardView`와 같은 자리에 놓이는 표시 전용 컴포넌트. `.kv` 그리드로
**증상 · 에러 · 환경 · 시도 · 원인 · 해결 · 상태**를 낸다.

- 시도는 판정 기호를 앞세운 목록: `✗ failed` · `△ partial` · `✓ worked` · `·  unknown`.
  실패한 시도가 화면에서 지워지지 않는 게 이 카드의 핵심 가치다.
- 에러는 시그니처를 헤드라인으로, **원문은 기본 접힘**(지식 카드의 '원문 정리'와 같은 방식).
  시그니처와 원문이 같으면 접기 버튼을 만들지 않는다.
- 값이 없는 항목은 **라벨조차 렌더하지 않는다**. 상태만 항상 표시한다(해결 여부는 이 카드의 결론).

### 유형 분기

- `ReviewDetailPage`: 인라인 4칸 블록 → `<TroubleshootingCardView fields={c.ts} />`.
- `MemoryDetailPage`: `type`으로 분기해 TS면 `structured`에서 카드를 읽어 렌더.

### 모델·어댑터

- `TsFields`를 백엔드 `TroubleshootingCard` 스키마로 교체(`summary`·`symptom`·`errorMessage`·
  `errorSignature`·`environment`·`attempts[]`·`rootCause`·`finalSolution`·`status`).
  목업의 `problem/tried/solution` 3필드는 정보를 잃어 폐기.
- `api/adapter.ts`에 `readTroubleshootingCard`(unknown 맵 → 타입 있는 카드)·`toTsFields`·
  `toTsStatus`를 추가. `structured`가 `Record<string, unknown>`이라 **`as` 단언 없이 필드별로
  좁혀서** 읽는다(`isObject`·`str`·`strList` 가드).
- `lib/search.ts`의 `memSummary`·`findSimilarMemory`를 새 필드로 정렬.

### 목록 해결상태 (백엔드 1줄 확장)

`MemoryResponse`에 `cardStatus`를 추가하고 `MemoryService.toResponse`가 `structured`의
`status`를 읽어 싣는다. 목록 조회는 이미 엔티티를 로드하므로 **추가 쿼리·스키마 변경이 없다**.

## 설계 판단

- **상태 이름을 둘로 구분한다** — `status`는 기억의 수명(active·archived·incorrect, "삭제 대신 상태
  보존"), `cardStatus`는 카드 내용의 상태(해결 여부). 한 이름에 섞으면 목록 배지와 숨김/폐기 배지가
  같은 필드를 다투게 된다.
- **`cardStatus`는 유형별 필드가 아니라 "카드가 두면 쓰이는 공통 선택 필드"로 다룬다** —
  `structured.status`를 그대로 읽고, 그 필드가 없는 유형은 null. `switch(MemoryType)` 없이 유형이
  늘어도 그대로 동작한다(`ReviewService`가 `title·summary·keywords`를 읽는 것과 같은 취급).
- **모르는 status → '미해결'** — 백엔드 정규화와 같은 규칙. 해결됐다고 잘못 단정하는 쪽이 반대보다
  위험하다. 반대로 목록에서 **값이 없으면 배지를 그리지 않는** 선택지도 있었지만, 실제 값을 싣는
  쪽이 PRD의 unresolved 페널티·resolved boost로 이어지는 길이라 백엔드 확장을 택했다.
- **`unknown` 판정을 성공/실패로 위장하지 않는다** — 기호를 `·`로 따로 두고 색도 흐리게 한다.
- **컴포넌트는 표시 전용** — 데이터 페칭·파싱은 페이지와 adapter에 남기고 이 컴포넌트는 props만
  받는다(frontend/CLAUDE.md의 표시/로직 분리).

## 검증

`npm run build`(tsc --noEmit + vite build) · `npm run lint` · `npm run format:check` 통과.
백엔드 단위테스트 114개 통과(DTO 필드 추가 후 재실행).

브라우저 실물 확인은 못 했다 — Docker(pgvector)가 안 떠서 백엔드를 띄울 수 없어 실제 TS 카드를
화면에 태우지 못했다. 렌더 분기·빈 값 생략·판정 기호는 타입체크와 코드 수준에서만 확인된 상태다.

## 범위 밖 / 후속

- **재발 배지(`hits`)** — `RecurBadge`는 아직 1 고정(백엔드 미지원).
- **`MemoryDetailResponse`의 knowledge 평면 필드**(`keywords`·`facts`·`document`) — 이제 knowledge
  렌더만 쓰는 레거시. knowledge 렌더도 `structured` 기반으로 옮기면 제거할 수 있다.
- **물어보기 화면** — 근거 카드는 제목만 보여준다. TS 근거를 증상·해결까지 펼치는 건 별건.
- **키워드 노출** — `MemoryResponse`에 keywords가 없어 목록 검색 하이라이트는 여전히 제한적.
