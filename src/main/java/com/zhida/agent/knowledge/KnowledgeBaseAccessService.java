package com.zhida.agent.knowledge;

import com.zhida.agent.auth.KnowledgeScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 把用户可见的知识库 ID 解析成文档存储和 Agent 工具使用的内部向量命名空间。
 *
 * <p>所有入口都必须复用这个服务。仅对 owner 和 ID 做哈希只能隔离不同用户的向量，
 * 不能证明当前用户真的创建过这个知识库。将“存在性、归属关系、内部命名空间生成”
 * 放在同一个边界中，可以防止文档接口和 Agent 执行入口逐渐产生不同的鉴权规则。</p>
 */
@Service
public class KnowledgeBaseAccessService {

    public static final String DEFAULT_KNOWLEDGE_BASE_ID = "default";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,50}");

    private final KnowledgeBaseRepository repository;

    public KnowledgeBaseAccessService(Optional<KnowledgeBaseRepository> repository) {
        this.repository = repository.orElse(null);
    }

    public String resolve(String owner, String requestedId) {
        String publicId = normalize(requestedId);

        /*
         * 持久化模式下，自定义 ID 必须存在对应的 owner 记录。否则调用者可以不断构造
         * 新 ID，制造无限个向量命名空间，从而绕过“每个知识库最多上传若干文档”的限制。
         * 未启用持久化时不存在知识库集合表，因此继续允许合法的临时 ID，兼容原有的
         * 本地单用户开发方式和无数据库冒烟测试。
         */
        if (!DEFAULT_KNOWLEDGE_BASE_ID.equals(publicId)
                && repository != null
                && !repository.exists(owner, publicId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
        }

        return KnowledgeScope.key(owner, publicId);
    }

    private String normalize(String requestedId) {
        String publicId = requestedId == null || requestedId.isBlank()
                ? DEFAULT_KNOWLEDGE_BASE_ID
                : requestedId.trim();
        if (!VALID_ID.matcher(publicId).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "知识库 ID 只能包含字母、数字、下划线和连字符，且不能超过 50 个字符"
            );
        }
        return publicId;
    }
}
