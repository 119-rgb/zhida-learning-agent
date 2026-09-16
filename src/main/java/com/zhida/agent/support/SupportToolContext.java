package com.zhida.agent.support;

/**
 * 保存一次工具执行对应的服务端认证账号。该值只由 Orchestrator 从已验证的 owner 写入，
 * 工具参数中不提供 userId/owner，避免模型或提示注入替换业务身份。
 */
public final class SupportToolContext {
  private static final ThreadLocal<String> OWNER = new ThreadLocal<>();

  private SupportToolContext() {}

  public static void set(String owner) {
    if (owner == null || owner.isBlank()) OWNER.remove();
    else OWNER.set(owner);
  }

  public static String currentOwner() {
    String owner = OWNER.get();
    if (owner == null) throw new IllegalStateException("缺少认证售后工具上下文");
    return owner;
  }

  public static void clear() {
    OWNER.remove();
  }
}
