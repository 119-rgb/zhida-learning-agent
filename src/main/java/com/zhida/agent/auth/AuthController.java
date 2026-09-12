package com.zhida.agent.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.security.Principal;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final ObjectProvider<HikariDataSource> source;
    private final PasswordEncoder passwords;
    private final boolean enabled;
    private final String secret;
    private final String dummyHash;
    public record Credentials(@Pattern(regexp="[a-zA-Z0-9_]{3,32}") @NotBlank String username,
                              @NotBlank @Size(min=8,max=64) String password) {
        @AssertTrue(message="密码 UTF-8 编码不能超过 72 字节")
        public boolean isPasswordWithinByteLimit() {
            return password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
        }
    }
    public record Token(String accessToken, long expiresIn, String username) {}
    public record Account(String id, String username, boolean guest) {}
    public AuthController(ObjectProvider<HikariDataSource> source, PasswordEncoder passwords,
                          @Value("${zhida.auth.enabled:false}") boolean enabled, @Value("${zhida.auth.secret:}") String secret) {
        this.source=source; this.passwords=passwords; this.enabled=enabled; this.secret=secret;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    @GetMapping("/config") public Map<String,Boolean> config() { return Map.of("enabled",enabled); }
    @GetMapping("/me") public Mono<Account> me(Principal principal) {
        return Mono.fromCallable(() -> {
            var accounts=jdbc().query("SELECT username,is_guest FROM user_account WHERE id=?",
                    (rs,row) -> new Account(principal.getName(),rs.getString(1),rs.getBoolean(2)),principal.getName());
            if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号不存在");
            return accounts.get(0);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private JdbcTemplate jdbc() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new JdbcTemplate(source.getObject());
    }
    @PostMapping("/register") public Mono<Token> register(@Valid @RequestBody Credentials request) {
        return Mono.fromCallable(() -> {
            var jdbc=jdbc(); String id=UUID.randomUUID().toString(); String name=request.username().toLowerCase(Locale.ROOT);
            try { jdbc.update("INSERT INTO user_account(id,username,password_hash,is_guest,created_at) VALUES (?,?,?,false,?)",
                    id,name,passwords.encode(request.password()),java.sql.Timestamp.from(Instant.now())); }
            catch (org.springframework.dao.DuplicateKeyException error) { throw new ResponseStatusException(HttpStatus.CONFLICT,"用户名已被使用"); }
            return token(id,name);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/login") public Mono<Token> login(@Valid @RequestBody Credentials request) {
        return Mono.fromCallable(() -> {
            var accounts=jdbc().queryForList("SELECT id,password_hash FROM user_account WHERE username=? AND is_guest=false", request.username().toLowerCase(Locale.ROOT));
            String hash=accounts.isEmpty()?dummyHash:String.valueOf(accounts.get(0).get("password_hash"));
            if (!passwords.matches(request.password(),hash) || accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户名或密码错误");
            return token(String.valueOf(accounts.get(0).get("id")),request.username().toLowerCase(Locale.ROOT));
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/refresh") public Mono<Token> refresh(Principal principal) {
        return Mono.fromCallable(() -> {
            var names=jdbc().queryForList("SELECT username FROM user_account WHERE id=?",String.class,principal.getName());
            if (names.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            return token(principal.getName(),names.get(0));
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/guest") public Mono<Token> guest(org.springframework.web.server.ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            String id=UUID.randomUUID().toString(); String name="guest_"+id;
            var remote=exchange.getRequest().getRemoteAddress();
            String ip=remote==null?"unknown":remote.getAddress().getHostAddress();
            String origin=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((secret+":"+ip).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var dataSource=source.getObject();
            var tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            tx.executeWithoutResult(status -> {
                var jdbc=new JdbcTemplate(dataSource);
                jdbc.update("INSERT INTO user_account(id,username,password_hash,is_guest,created_at) VALUES (?,?,?,true,?)",
                        id,name,"DISABLED",java.sql.Timestamp.from(Instant.now()));
                jdbc.update("INSERT INTO guest_origin(user_id,origin_hash) VALUES (?,?)",id,origin);
            });
            return token(id,name);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private Token token(String id,String name) throws JOSEException {
        Instant now=Instant.now();
        var claims=new JWTClaimsSet.Builder().issuer("zhida").subject(id).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600))).jwtID(UUID.randomUUID().toString()).build();
        var signed=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims);
        signed.sign(new MACSigner(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new Token(signed.serialize(),3600,name);
    }
}
