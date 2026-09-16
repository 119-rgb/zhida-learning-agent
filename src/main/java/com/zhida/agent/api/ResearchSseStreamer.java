package com.zhida.agent.api;

import com.zhida.agent.application.ResearchSession;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把已准备好的 {@link ResearchSession} 通过 SSE 推送给浏览器。研究助手和售后助手共用这段逻辑，
 * 因此取消、超时、事件编号和队列已满的处理只有一份实现，不会因为新增入口而产生行为差异。
 *
 * <p>调用方必须在调用本方法前完成身份解析和知识库授权校验：这个方法只负责传输，不做权限判断。
 */
public final class ResearchSseStreamer {
  private ResearchSseStreamer() {}

  public static SseEmitter stream(
      ResearchSession session, ThreadPoolTaskExecutor executor, long timeoutSeconds) {
    SseEmitter emitter = new SseEmitter(timeoutSeconds * 1_000L + 5_000L);
    AtomicBoolean closed = new AtomicBoolean();
    // 断线、超时和主动取消最终都走同一个 cancel，保证任务只被取消一次且会话锁一定释放。
    Runnable cancel =
        () -> {
          if (closed.compareAndSet(false, true)) session.cancel();
        };
    emitter.onTimeout(cancel);
    emitter.onError(error -> cancel.run());
    emitter.onCompletion(cancel);
    try {
      executor.execute(
          new ResearchExecutorConfiguration.CancellableWork(
              cancel,
              () -> {
                try {
                  session.execute(
                      event -> {
                        if (closed.get()) throw new IllegalStateException("SSE connection closed");
                        try {
                          emitter.send(
                              SseEmitter.event()
                                  .id(Long.toString(event.eventId()))
                                  .name(event.type())
                                  .data(event));
                        } catch (IOException | IllegalStateException error) {
                          cancel.run();
                          throw new IllegalStateException("SSE send failed", error);
                        }
                      });
                  if (closed.compareAndSet(false, true)) emitter.complete();
                } catch (Exception error) {
                  cancel.run();
                  emitter.completeWithError(error);
                }
              }));
    } catch (TaskRejectedException error) {
      cancel.run();
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Agent 队列已满", error);
    }
    return emitter;
  }
}
