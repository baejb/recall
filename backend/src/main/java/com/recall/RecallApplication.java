package com.recall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Recall — 단일 사용자 셀프호스트 개인 기억 시스템.
 *
 * <p>지금은 "실행되는 최소 골격"이다. 도메인 모듈(capture/search/review/…)은 기능 구현 시 추가한다.
 */
@EnableAsync
@SpringBootApplication
public class RecallApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecallApplication.class, args);
    }
}
