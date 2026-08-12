package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ModelSettingRepositoryTest {

    @Autowired ModelSettingRepository repository;

    @Test
    void seedRowExists() {
        ModelSetting s = repository.findById(1L).orElseThrow();
        assertEquals("anthropic", s.getChatProvider());
        assertEquals("READY", s.getEmbeddingStatus());
    }
}
