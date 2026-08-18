package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ModelSettingUserIdMigrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V12: model_setting에 user_id NOT NULL UNIQUE FK, 부트스트랩 행은 user_id=1")
    void migratesToPerUser() {
        Integer col =
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_name='model_setting' AND column_name='user_id' AND"
                                + " is_nullable='NO'",
                        Integer.class);
        assertEquals(1, col, "user_id NOT NULL 컬럼 존재");
        Long owner =
                jdbc.queryForObject("SELECT user_id FROM model_setting WHERE id=1", Long.class);
        assertEquals(1L, owner, "기존 단일행은 부트스트랩(1)로 귀속");
        Integer uq =
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_constraint WHERE conname='uq_model_setting_user'",
                        Integer.class);
        assertEquals(1, uq, "UNIQUE(user_id) 제약 존재");
    }
}
