package com.recall.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 개발용 CORS: Vite 개발 서버가 API 를 호출한다(FRONTEND_PORT 로 포트가 바뀔 수 있어 localhost 임의 포트를 허용한다). 배포에서는 nginx
 * 가 /api 를 동일 오리진으로 프록시하므로 이 설정은 로컬 개발에만 관여한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }
}
