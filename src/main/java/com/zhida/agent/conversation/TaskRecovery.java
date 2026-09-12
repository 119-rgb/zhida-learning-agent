package com.zhida.agent.conversation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="zhida.persistence.enabled",havingValue="true")
public class TaskRecovery {
    private final TaskRepository repository;
    public TaskRecovery(TaskRepository repository) { this.repository=repository; }
    @Scheduled(fixedDelay=60000,initialDelay=10000)
    public void recover() {
        // All configured tasks are bounded by 600 seconds. Extra margin avoids racing completion.
        repository.recoverExpired(java.time.Instant.now().minusSeconds(720));
    }
}
