# 설계 문서 (슬라이스별)

기능을 얇은 수직 슬라이스로 나눠 구현한다. 각 슬라이스의 **왜·무엇을·설계 판단·검증·범위 밖**을
한 문서로 남긴다(커밋 메시지보다 정돈된 참고용). 되돌리기 어려운 결정은 `docs/setup.md`의
Decision Log에도 요약한다.

## Phase 1 — knowledge 유형

| # | 슬라이스 | 문서 | 상태 |
|---|----------|------|------|
| 1 | S2 구조화 추출 (+ 프롬프트 리소스 분리) | [knowledge-01-s2-extraction.md](knowledge-01-s2-extraction.md) | 완료 |
| 2 | 검색 — Voyage 임베딩 + vector·BM25 하이브리드(RRF) | [knowledge-02-search.md](knowledge-02-search.md) | 완료 |
| 3 | S4 판정 — 유사 후보 대조(재발/보완/충돌) | [knowledge-03-s4-judgement.md](knowledge-03-s4-judgement.md) | 완료 |

## Phase 2 — troubleshooting 유형

| # | 슬라이스 | 문서 | 상태 |
|---|----------|------|------|
| 1 | 유형 전략 SPI 5종 + 저장 경로 유형 라우팅 | [troubleshooting-01-type.md](troubleshooting-01-type.md) | 완료(백엔드) |
| 2 | 프론트 TS 카드 렌더 + 목록 해결상태 | [troubleshooting-02-frontend.md](troubleshooting-02-frontend.md) | 완료 |

> 공유 파이프라인·SPI 계약의 큰 그림은 `docs/architecture.md`, 기능 근거는 `docs/recall_ai_prd.md`.
