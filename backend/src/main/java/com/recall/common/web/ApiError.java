package com.recall.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recall.common.exception.ErrorCode;
import java.util.UUID;

/**
 * 실패 응답의 본문 — {@code code · message · field · traceId}.
 *
 * <p>{@code traceId}를 여기서 만드는 이유: 사용자가 화면에서 보고할 수 있는 식별자와 서버 로그가 <b>같은 값</b>을 가져야 한다. 봉투를 만드는 지점이
 * 하나여야 그 상관이 보장된다 — 로그에만 있고 응답에 없는 id, 응답에만 있고 로그에 없는 id 는 둘 다 추적에 쓸 수 없다.
 *
 * @param code {@link ErrorCode} 이름 — 클라이언트가 분기하는 안정된 식별자(한국어 메시지 문자열을 비교하지 않게)
 * @param message 사람이 읽는 설명. 내부 타입·클래스 이름·자격증명을 담지 않는다
 * @param field 필드 단위 오류일 때만 채운다 — null 이면 직렬화에서 빠진다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String field, String traceId) {

    public static ApiError of(ErrorCode code, String message, String field) {
        return new ApiError(code.name(), message, field, UUID.randomUUID().toString());
    }
}
