package com.zhida.agent.knowledge;

import com.zhida.agent.auth.KnowledgeScope;
import com.zhida.agent.support.SupportActorResolver;
import com.zhida.agent.support.SupportRole;
import org.springframework.beans.factory.annotation.Autowired;
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
    public static final String PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID = "product-support";
    private static final String PUBLIC_PRODUCT_NAMESPACE =
            KnowledgeScope.key("zhida-system-public", PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID);
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,50}");

    private final KnowledgeBaseRepository repository;
    private final SupportActorResolver supportActors;

    public KnowledgeBaseAccessService(Optional<KnowledgeBaseRepository> repository) {
        this(repository, Optional.empty());
    }

    @Autowired
    public KnowledgeBaseAccessService(
            Optional<KnowledgeBaseRepository> repository,
            Optional<SupportActorResolver> supportActors) {
        this.repository = repository.orElse(null);
        this.supportActors = supportActors.orElse(null);
    }

    /** 解析只读访问；公共售后资料对所有正式账号开放，私人资料仍按 owner 隔离。 */
    public String resolve(String owner, String requestedId) {
        String publicId = logicalId(requestedId);

        if (PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID.equals(publicId)) {
            requirePublicAccount(owner);
            /*
             * 公共内部命名空间必须无法由旧版本的外部 ID 构造。旧本地模式会把合法 ID
             * 原样作为命名空间；若这里直接使用 product-support，升级前同名私人文档会
             * 被误公开。带系统域分隔的 64 位哈希超过外部 ID 上限，可避免该碰撞。
             */
            return PUBLIC_PRODUCT_NAMESPACE;
        }

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

    /**
     * 解析上传、重试和删除等维护操作。公共产品知识库只能由数据库中的 ADMIN 角色维护；
     * 私人知识库继续由当前 owner 自主管理。
     */
    public String resolveForWrite(String owner, String requestedId) {
        String publicId = logicalId(requestedId);
        if (PUBLIC_PRODUCT_KNOWLEDGE_BASE_ID.equals(publicId)) {
            SupportActorResolver.Actor actor = requirePublicAccount(owner);
            if (actor.role() != SupportRole.ADMIN) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有管理员可以维护公共产品知识库");
            }
            return PUBLIC_PRODUCT_NAMESPACE;
        }
        return resolve(owner, publicId);
    }

    /** 集合页只在售后模块可用且账号有效时展示公共知识库。 */
    public boolean canReadPublic(String owner) {
        if (supportActors == null) return false;
        try {
            supportActors.account(owner);
            return true;
        } catch (ResponseStatusException ignored) {
            return false;
        }
    }

    /** writable 标记只用于页面提示，真正的维护接口仍会再次查库校验角色。 */
    public boolean canMaintainPublic(String owner) {
        if (supportActors == null) return false;
        try {
            return supportActors.account(owner).role() == SupportRole.ADMIN;
        } catch (ResponseStatusException ignored) {
            return false;
        }
    }

    private SupportActorResolver.Actor requirePublicAccount(String owner) {
        if (supportActors == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "公共产品知识库未启用");
        }
        return supportActors.account(owner);
    }

    /** 返回对外使用的规范 ID，避免授权按 trim 后 ID 执行而响应仍回显未规范化路径。 */
    public String logicalId(String requestedId) {
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
