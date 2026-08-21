package com.recall.settings;

/**
 * 임베딩 provider/model/base-url 이 실제로 바뀌었을 때 발행되는 도메인 이벤트. 수신자(재색인)는 {@code com.recall.search} 모듈에
 * 있어, 이 이벤트로 결합을 끊는다(SettingsService → ReindexService 직접 의존 시 빈 순환: SettingsService →
 * ReindexService → EmbeddingClient → SettingsService).
 *
 * @param userId 이 설정 변경을 일으킨 사용자(설정 CRUD 는 항상 currentUser 스코프). 재색인은 이 값으로 {@code
 *     AiContextFactory#forUser}를 호출해 컨텍스트를 고정한다 — 재색인 배경 스레드는 요청 스레드의 {@code CurrentUserProvider}에
 *     의존하지 않는다(교차유출 방지).
 * @param generation 이 변경으로 부여된 재색인 세대 토큰. 배경 재색인 잡은 이 값을 들고 돌며, 종료 시 {@code userId} 소유 행의 현재 세대가 아직
 *     자기 세대와 같을 때만 상태(READY/FAILED)를 쓴다(뒤늦은 앞선 잡이 새 재색인 위에 READY 를 덮어쓰지 못하게 하는 펜싱 토큰, user_id 스코프).
 */
public record EmbeddingModelChangedEvent(long userId, long generation) {}
