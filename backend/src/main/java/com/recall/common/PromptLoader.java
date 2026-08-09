package com.recall.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * classpath의 프롬프트 리소스를 읽어 캐시한다. 프롬프트는 코드가 아니라 콘텐츠라 소스에 하드코딩하지 않고 {@code
 * src/main/resources/prompts/}에 파일로 둔다(튜닝·버전비교·단계별 관리가 쉽도록).
 *
 * <p>결정론 단계가 아니라 단순 리소스 로딩이므로 LLM과 무관하다. 없는 경로는 조용히 넘기지 않고 예외로 드러낸다(조용한 실패 금지).
 */
@Component
public class PromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * classpath 상대 경로의 프롬프트를 읽어 반환한다(최초 1회 로드 후 캐시).
     *
     * @param path 예: {@code "prompts/knowledge-extraction.md"}
     */
    public String load(String path) {
        return cache.computeIfAbsent(path, PromptLoader::read);
    }

    private static String read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트 리소스를 읽지 못함: " + path, e);
        }
    }
}
