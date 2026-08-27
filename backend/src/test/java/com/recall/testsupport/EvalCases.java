package com.recall.testsupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Eval 라벨셋 로더 — {@code src/test/resources/eval/*.json} 의 케이스를 읽는다.
 *
 * <p><b>왜 케이스를 코드가 아니라 데이터로 두나</b> — PRD §7.1 은 단계마다 케이스를 <b>한 줄씩 늘려가며</b> 자산으로 만들라고 한다. 케이스가 자바 코드
 * 안에 있으면 실패를 하나 발견할 때마다 테스트 메서드를 고쳐야 하고, 그러면 "케이스를 추가한 변경"과 "채점 규칙을 바꾼 변경"이 같은 diff 에 섞여 나중에 임계값이 왜
 * 그 값인지 되짚을 수 없다. 데이터로 두면 케이스 추가가 JSON 한 줄이고, 채점기 변경은 별개 diff 로 남는다.
 *
 * <p>스키마는 단계마다 다르므로(마스킹은 secrets·expect, 라우팅은 expectedType 등) 여기서는 {@code Map} 으로 읽고 각 채점기가 자기 필드를
 * 꺼낸다 — 케이스 파일은 테스트 리소스이고 모듈 경계 계약이 아니라, Map 금지 규칙(java-spring.md §4)의 대상이 아니다.
 */
public final class EvalCases {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalCases() {}

    /**
     * {@code eval/<name>} 라벨셋을 읽는다.
     *
     * @param name 리소스 경로(예: {@code "eval/masking-m0.json"})
     * @throws IllegalStateException 파일이 없거나 깨졌을 때 — 라벨셋이 사라진 채로 테스트가 "통과"하면 안 된다(빈 셋은 0건 검증이라 항상
     *     초록이다). Eval 의 실패 모드는 "케이스가 조용히 비는 것"이라 여기서 요란하게 막는다.
     */
    public static List<Map<String, Object>> load(String name) {
        try (InputStream in = EvalCases.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Eval 라벨셋을 찾을 수 없다: " + name);
            }
            List<Map<String, Object>> cases =
                    MAPPER.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
            if (cases.isEmpty()) {
                throw new IllegalStateException("Eval 라벨셋이 비어 있다: " + name);
            }
            return cases;
        } catch (Exception e) {
            throw new IllegalStateException("Eval 라벨셋 로드 실패: " + name, e);
        }
    }

    /** 케이스의 문자열 필드. 없으면 빈 문자열. */
    public static String str(Map<String, Object> c, String key) {
        Object v = c.get(key);
        return v == null ? "" : v.toString();
    }

    /** 케이스의 문자열 리스트 필드. 없으면 빈 리스트. */
    public static List<String> list(Map<String, Object> c, String key) {
        Object v = c.get(key);
        if (!(v instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).toList();
    }
}
