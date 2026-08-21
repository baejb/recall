package com.recall.search;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 재색인 배경 잡 전용 executor. corePoolSize=maxPoolSize=1 로 재색인을 <b>직렬화</b>한다 — 임베딩 설정을 연달아 바꿔도 재색인 잡이 병렬로
 * 돌지 않고 큐에 쌓여 한 번에 하나씩 실행된다.
 *
 * <p>기본 executor(SimpleAsyncTaskExecutor)는 스레드-퍼-태스크라 병렬 실행돼, 앞선 잡이 아직 부분 재색인 중인데 뒤 잡이 끼어들거나 같은
 * {@code memory_embedding} 행에 동시 upsert 하는 위험이 있다. 단일 스레드 큐로 그 경합을 원천 차단한다(세대 펜싱과 함께 "마지막 잡만 READY
 * 를 쓴다"는 보장을 성립시킨다).
 */
@Configuration
public class ReindexConfig {

    @Bean(name = "reindexExecutor")
    public Executor reindexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("reindex-");
        executor.initialize();
        return executor;
    }
}
