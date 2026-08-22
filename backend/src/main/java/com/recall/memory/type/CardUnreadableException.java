package com.recall.memory.type;

import com.recall.common.type.MemoryType;

/**
 * 저장된 <b>그 한 건</b>의 카드 JSON을 읽을 수 없다 — 데이터 쪽 문제.
 *
 * <p><b>왜 전용 타입인가</b> — {@link CardCodec#readOrNull} 은 "한 건을 건너뛰고 나머지를 살린다"는 격하 장치다. 그런데 격하 범위를
 * {@code RuntimeException} 으로 잡으면 <b>성격이 전혀 다른 두 실패가 같은 신호가 된다</b>: (1) 이 행의 JSON이 카드 스키마와 안
 * 맞는다(데이터 한 건), (2) 이 유형에 추출 전략이 등록되지 않았다(배포·배선 결함). 전자는 건너뛰는 게 맞지만 후자는 <b>그 유형의 모든 카드</b>에 해당하므로
 * 건너뛰면 안 된다.
 *
 * <p>실제로 그렇게 뭉갰을 때 나온 결말: 재색인이 그 유형 카드를 전부 "손상"으로 판정해 임베딩을 지우고도 잡을 {@code READY} 로 마쳤고(벡터 채널은 정상으로
 * 표시된 채 그 유형이 검색에서 사라진다), 조회는 근거가 있는데 "기록 없음"을 냈고, 승인은 사용자가 고칠 수 없는 상황을 409로 보고했다. 배선 결함은 격하 대상이
 * 아니라 <b>500으로 드러나야 하는 결함</b>이다(조용한 실패 금지).
 *
 * <p>그래서 전략 조회는 try 밖에서 하고(실패하면 그대로 전파), 이 예외는 <b>역직렬화 실패에만</b> 붙는다.
 */
public class CardUnreadableException extends RuntimeException {

    public CardUnreadableException(MemoryType type, Throwable cause) {
        super("구조화 카드 파싱 실패(type=" + type + ")", cause);
    }
}
