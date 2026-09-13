package com.recall.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * auth 모듈 배선. {@link AuthProperties} 를 부트스트랩 모드에서도 등록하는 이유: {@code /api/me} 와 허용목록 검사가 프로필과 무관하게 같은
 * 타입을 주입받아야 하고, 프로필별로 빈 존재 여부가 갈리면 그 자체가 오설정의 원인이 된다(모드에 따라 주입이 실패하는 코드).
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {}
