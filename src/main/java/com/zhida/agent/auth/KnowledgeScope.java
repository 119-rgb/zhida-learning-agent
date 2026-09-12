package com.zhida.agent.auth;

public final class KnowledgeScope {
    private KnowledgeScope() {}
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    public static String key(String owner, String base) {
        String id = base == null ? "default" : base;
        if (!id.matches("[A-Za-z0-9_-]{1,50}")) throw new IllegalArgumentException("知识库 ID 不合法");
        if (owner.equals("local-user")) return id;
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((owner + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public static void set(String key) { CURRENT.set(key); }
    public static String current() {
        String key=CURRENT.get();
        if (key == null) throw new IllegalStateException("缺少授权知识库上下文");
        return key;
    }
    public static void clear() { CURRENT.remove(); }
}
