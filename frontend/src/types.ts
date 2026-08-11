// 목업(디자인 아티팩트)의 데이터 모델을 그대로 미러링한 타입.
// mock 단계 전용 — 실제 백엔드 연동 시 /api 응답 인터페이스로 교체·정렬한다.

/** 메모리 유형. ts=트러블슈팅, kn=지식. */
export type MemoryTypeKey = 'ts' | 'kn'

/** 트러블슈팅 해결 상태. */
export type TsStatus = '해결' | '부분' | '미해결'

/** 메모리 활성 상태(삭제 대신 상태 전이 — 불변 원칙). */
export type MemoryStatus = 'active' | 'archived'

/** 마스킹으로 가린 비밀 스팬(어떤 키를 가렸는지). */
export interface MaskSpan {
  key: string
}

/** 원문 캡처(근거 전용, 검색 대상 아님). */
export interface Capture {
  id: string
  masked: string
  spans: MaskSpan[]
  created: string
}

/** 트러블슈팅 유형 필드. */
export interface TsFields {
  problem: string
  tried: string
  solution: string
  status: TsStatus
}

/** 지식 유형 필드. */
export interface KnFields {
  content: string
}

/** 승인된 기억 카드. 원문 1개에서 여러 개가 나올 수 있다(1:N). */
export interface Memory {
  id: string
  captureId: string
  type: MemoryTypeKey
  title: string
  created: string
  status: MemoryStatus
  firstSeen: string
  lastSeen: string
  /** 같은 문제를 마주친 횟수(재발 카운트). */
  hits: number
  keywords: string
  ts: TsFields
  kn: KnFields
}

/** 검토 대기 카드(승인 전 후보). */
export interface ReviewCard {
  type: MemoryTypeKey
  title: string
  ts: TsFields
  kn: KnFields
}

/** 검토 대기 항목(원본 1개 → 카드 여러 개). */
export interface Review {
  id: string
  captureId: string
  cards: ReviewCard[]
}

/** 물어보기 범위 필터. */
export type QueryScope = '전체' | 'ts' | 'kn'
