package com.zhida.agent.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ResearchExecutorConfiguration {
  @Bean
  public ThreadPoolTaskExecutor researchExecutor() {
    ThreadPoolTaskExecutor executor =
        new ThreadPoolTaskExecutor() {
          @Override
          protected void cancelRemainingTask(Runnable task) {
            if (task instanceof CancellableWork work) work.cancel();
            super.cancelRemainingTask(task);
          }
        };
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(32);
    executor.setThreadNamePrefix("research-");
    executor.setWaitForTasksToCompleteOnShutdown(false);
    return executor;
  }

  static final class CancellableWork implements Runnable {
    private final Runnable cancel;
    private final Runnable work;

    CancellableWork(Runnable cancel, Runnable work) {
      this.cancel = cancel;
      this.work = work;
    }

    @Override
    public void run() {
      work.run();
    }

    void cancel() {
      cancel.run();
    }
  }
}
