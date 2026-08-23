package com.recall.memory;

import com.recall.common.type.MemoryType;

/**
 * 모듈 밖으로 나가는 memory 의 <b>불변 표현</b>.
 *
 * <p><b>왜 엔티티를 그대로 주지 않나</b> — {@code Memory} 는 JPA 엔티티라 가변이고 영속 컨텍스트에 묶여 있다. 그걸 다른 모듈에 넘기면 (1) 남의
 * 모듈이 세터로 상태를 바꿀 수 있고, (2) dirty checking 이 언제 flush 되는지가 호출자 코드에 보이지 않는 곳에서 결정되고, (3) 트랜잭션 밖으로 나가면
 * lazy 필드 접근이 터진다. 실제로 query·search·store 세 모듈이 이 엔티티를 손에 들고 있었다.
 *
 * <p>필드가 셋뿐인 이유: 모듈 밖에서 실제로 읽는 것이 <b>id · 유형 · 저장된 카드 JSON</b> 뿐이다. 제목·상태·시각은 memory 모듈 안에서만 쓰인다.
 * 계약은 필요한 만큼만 넓히는 게 맞다 — 넓혀 두면 그만큼이 나중에 못 바꾸는 약속이 된다.
 *
 * @param id memory.id
 * @param type 이 카드의 유형(전략 선택 키)
 * @param structured 저장된 구조화 카드 JSON — 읽는 쪽은 {@code CardCodec} 으로 유형 스키마를 거쳐 되읽는다
 */
public record StoredMemory(long id, MemoryType type, String structured) {}
