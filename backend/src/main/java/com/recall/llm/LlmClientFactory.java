package com.recall.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 설정 → 클라이언트. 등록된 {@link ChatProvider} 서술자만으로 디스패치한다(하드코딩 switch 없음). 가용 provider = 주입된 서술자
 * 목록이므로 카탈로그와 팩토리가 같은 원천을 공유해 드리프트가 구조적으로 불가능하다.
 *
 * <p>동일 설정(provider|model|baseUrl|key 지문)은 캐시 재사용. 키 지문은 충돌 저항 SHA-256({@link ApiKeyFingerprint}) —
 * 32비트 hashCode 는 충돌 시 다른 사용자의 키로 만든 클라이언트를 반환할 수 있다.
 */
public class LlmClientFactory {

    private final Map<String, ChatProvider> byName;
    private final Map<String, LlmClient> cache = new ConcurrentHashMap<>();

    public LlmClientFactory(List<ChatProvider> providers) {
        Map<String, ChatProvider> map = new HashMap<>();
        for (ChatProvider p : providers) {
            ChatProvider previous = map.put(p.name().toLowerCase(Locale.ROOT), p);
            if (previous != null) {
                throw new IllegalStateException("한 provider 이름에 chat 서술자가 둘 이상 등록됨: " + p.name());
            }
        }
        this.byName = Map.copyOf(map);
    }

    public LlmClient forSettings(LlmProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return new StubLlmClient();
        }
        ChatProvider provider = byName.get(props.provider().toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new IllegalStateException("등록되지 않은 provider: " + props.provider());
        }
        String cacheKey =
                props.provider()
                        + "|"
                        + props.model()
                        + "|"
                        + props.baseUrl()
                        + "|"
                        + ApiKeyFingerprint.of(props.apiKey());
        return cache.computeIfAbsent(cacheKey, k -> provider.create(props));
    }
}
