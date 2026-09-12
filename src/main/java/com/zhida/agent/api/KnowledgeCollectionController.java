package com.zhida.agent.api;

import com.zhida.agent.auth.OwnerResolver;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@ConditionalOnProperty(name="zhida.persistence.enabled",havingValue="true")
public class KnowledgeCollectionController {
    private final JdbcTemplate jdbc;
    private final OwnerResolver owners;
    public KnowledgeCollectionController(HikariDataSource source,OwnerResolver owners) { this.jdbc=new JdbcTemplate(source);this.owners=owners; }
    public record Base(String id,String name) {}
    public record Create(@NotBlank @Size(max=100) String name) {}
    @GetMapping public Mono<List<Base>> list(Principal principal) {
        return Mono.fromCallable(() -> {
            List<Base> list=new ArrayList<>();list.add(new Base("default","默认知识库"));
            list.addAll(jdbc.query("SELECT id,name FROM knowledge_base WHERE user_id=? ORDER BY name,id",(rs,row)->new Base(rs.getString(1),rs.getString(2)),owners.owner(principal)));
            return list;
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping public Mono<Base> create(@Valid @RequestBody Create request,Principal principal) {
        return Mono.fromCallable(() -> {
            Base base=new Base(UUID.randomUUID().toString(),request.name().trim());
            jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,?)",base.id(),owners.owner(principal),base.name());
            return base;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
