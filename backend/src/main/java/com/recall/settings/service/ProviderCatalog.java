package com.recall.settings.service;

import com.recall.common.exception.ValidationException;
import com.recall.llm.ChatProvider;
import com.recall.llm.EmbeddingProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 역할별 허용 provider·모델 카탈로그 + 검증. capability 비대칭(설계 §2.1).
 *
 * <p>가용 provider·추천 모델은 <b>등록된 서술자 빈</b>({@link ChatProvider}/{@link EmbeddingProvider})에서만 파생된다.
 * 하드코딩된 provider Set 이 없으므로 카탈로그↔팩토리 드리프트(한쪽만 아는 provider)가 구조적으로 사라진다 — 팩토리도 같은 서술자 목록을 쓴다.
 */
@Component
public class ProviderCatalog {

    public enum Role {
        CHAT,
        EMBEDDING
    }

    private final Map<String, List<String>> chatModels;
    private final Map<String, List<String>> embeddingModels;

    public ProviderCatalog(List<ChatProvider> chats, List<EmbeddingProvider> embs) {
        this.chatModels =
                chats.stream()
                        .collect(
                                Collectors.toMap(
                                        c -> c.name().toLowerCase(Locale.ROOT),
                                        c -> List.copyOf(c.recommendedModels()),
                                        (a, b) -> {
                                            throw new IllegalStateException("중복 chat provider 등록");
                                        },
                                        LinkedHashMap::new));
        this.embeddingModels =
                embs.stream()
                        .collect(
                                Collectors.toMap(
                                        e -> e.name().toLowerCase(Locale.ROOT),
                                        e -> List.copyOf(e.recommendedModels()),
                                        (a, b) -> {
                                            throw new IllegalStateException(
                                                    "중복 embedding provider 등록");
                                        },
                                        LinkedHashMap::new));
    }

    public boolean supports(Role role, String provider) {
        if (provider == null) {
            return false;
        }
        return models(role).containsKey(provider.toLowerCase(Locale.ROOT));
    }

    public void requireSupported(Role role, String provider) {
        if (!supports(role, provider)) {
            // 400 — 설정 저장 요청 값의 검증 실패다(PUT /api/settings/models). 전에는
            // IllegalArgumentException 이라 전역 핸들러가 "모든 IllegalArgumentException = 400" 이라는
            // 너무 넓은 규칙으로 받아야 했고, 그 규칙은 내부 배선 버그(등록된 전략 없음)까지 400 으로 감췄다.
            throw new ValidationException(role + " 역할이 지원하지 않는 provider: " + provider, "provider");
        }
    }

    public Map<String, List<String>> chatModels() {
        return chatModels;
    }

    public Map<String, List<String>> embeddingModels() {
        return embeddingModels;
    }

    /** 역할별 등록 provider 집합(드리프트 검증·로깅용). */
    public Set<String> providers(Role role) {
        return models(role).keySet();
    }

    private Map<String, List<String>> models(Role role) {
        return role == Role.CHAT ? chatModels : embeddingModels;
    }
}
