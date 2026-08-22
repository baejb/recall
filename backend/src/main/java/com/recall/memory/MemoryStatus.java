package com.recall.memory;

import java.util.Set;

/**
 * 기억의 <b>수명 상태</b>({@code memory.status}) 어휘 — 이 컬럼을 소유한 memory 도메인이 어휘도 소유한다.
 *
 * <p><b>왜 모으나</b> — 같은 어휘가 네 곳에 리터럴로 흩어져 있었다: {@code MemoryService}의 package-private 상수, {@code
 * Memory} 엔티티의 필드 초기화({@code = "active"}), {@code MemoryController}의
 * {@code @RequestParam(defaultValue = "active")}, {@code MemorySearchStore}의 native SQL({@code
 * status = 'active'}). 어휘를 하나 옮기면 grep 이 유일한 안전망이었고, 실제로 {@code superseded → archived} 로 옮기는 과정에서
 * SQL 주석과 프론트 주석이 서로 다른 어휘를 말하는 상태가 남았다.
 *
 * <p><b>쿼리도 이 상수를 쓴다</b> — 한동안 이 홀더를 만들고도 {@code MemoryRepository} 의 JPQL 과 {@code
 * MemorySearchStore} 의 native SQL 두 개는 리터럴로 남아 있었다(이 문서가 문제로 지목한 바로 그 자리다). 쿼리 문자열은 컴파일러가 검사하지
 * 않으므로, 값을 바꾸면 컴파일은 통과하고 <b>검색·재색인 결과가 조용히 비어버린다</b>. {@code ACTIVE} 는 컴파일 상수라 {@code @Query} 애노테이션
 * 값에서도 연결할 수 있어 예외를 둘 이유가 없다.
 *
 * <p><b>왜 enum 이 아닌가</b> — 스키마는 Flyway 소유이고 컬럼은 CHECK 없는 {@code VARCHAR}다. JPA 매핑·native SQL·
 * {@code @RequestParam} 경계가 모두 문자열을 그대로 쓰므로, enum 으로 올리면 그 세 경계에 변환을 새로 깔아야 한다. 지금 필요한 건 "한 곳에서
 * 관리"이므로 상수 홀더로 둔다. (분기에 쓰이는 어휘 — {@code MemoryType}·{@code Verdict} — 는 이미 enum 이다.)
 *
 * <p>{@code superseded}는 여기 없다 — 충돌 처리에서 시스템이 설정하는 값이고 사용자 액션으로 전이·조회하는 대상이 아니다(그 흐름이 구현되면 이 홀더에 함께
 * 둔다).
 */
public final class MemoryStatus {

    /** 정상. */
    public static final String ACTIVE = "active";

    /** 숨김(소프트 제거, 복원 가능). */
    public static final String ARCHIVED = "archived";

    /** 폐기(틀린 정보). */
    public static final String INCORRECT = "incorrect";

    /** 사용자 액션으로 전이·조회를 허용하는 상태. */
    public static final Set<String> USER_SETTABLE = Set.of(ACTIVE, ARCHIVED, INCORRECT);

    private MemoryStatus() {}
}
