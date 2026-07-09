package com.recall;

import static org.assertj.core.api.Assertions.assertThat;

import com.recall.capture.MaskingService;
import org.junit.jupiter.api.Test;

/** Plain unit test — no Spring context / DB needed, so `gradlew test` stays green offline. */
class MaskingServiceTest {

    private final MaskingService maskingService = new MaskingService();

    @Test
    void masksApiKeyBeforeItCouldLeave() {
        var result = maskingService.mask("KAFKA_API_KEY=sk-live9f3a8b2c7d1e and password=mySecret123");

        assertThat(result.masked()).doesNotContain("sk-live9f3a8b2c7d1e");
        assertThat(result.masked()).doesNotContain("mySecret123");
        assertThat(result.masked()).contains("[MASKED_API_KEY]");
        assertThat(result.spans()).isNotEmpty();
    }
}
