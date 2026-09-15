package com.zhida.agent.knowledge;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 保存用户在页面上能够看到的知识库集合。
 *
 * <p>当前学习版项目仍使用本地 JSON 文件保存文档目录和向量索引；这个仓储只负责
 * MySQL 中的知识库集合元数据。把 SQL 收口在这里后，Controller 和 Agent 不需要
 * 知道表结构，并且可以统一判断一个公开的知识库 ID 是否属于指定用户。</p>
 */
@Repository
@ConditionalOnProperty(name = "zhida.persistence.enabled", havingValue = "true")
public class KnowledgeBaseRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeBaseRepository(HikariDataSource source) {
        this.jdbc = new JdbcTemplate(source);
    }

    public List<KnowledgeBase> list(String owner) {
        return jdbc.query(
                "SELECT id,name FROM knowledge_base WHERE user_id=? ORDER BY name,id",
                (resultSet, row) -> new KnowledgeBase(resultSet.getString(1), resultSet.getString(2)),
                owner
        );
    }

    public KnowledgeBase create(String owner, String name) {
        KnowledgeBase knowledgeBase = new KnowledgeBase(UUID.randomUUID().toString(), name.trim());
        jdbc.update(
                "INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,?)",
                knowledgeBase.id(),
                owner,
                knowledgeBase.name()
        );
        return knowledgeBase;
    }

    public boolean exists(String owner, String knowledgeBaseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE user_id=? AND id=?",
                Integer.class,
                owner,
                knowledgeBaseId
        );
        return count != null && count > 0;
    }

    public record KnowledgeBase(String id, String name) {
    }
}
