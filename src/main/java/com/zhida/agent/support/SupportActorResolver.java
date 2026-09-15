package com.zhida.agent.support;

import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Only controllers resolve the authenticated principal; roles never come from JWT claims or DTOs.
 */
public class SupportActorResolver {
  public record Actor(String id, SupportRole role) {}

  private final JdbcTemplate jdbc;

  public SupportActorResolver(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Actor resolve(Principal principal) {
    if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    return account(principal.getName());
  }

  public Actor account(String id) {
    var rows =
        jdbc.query(
            """
            SELECT u.is_guest,COALESCE(r.role,'USER') AS role FROM user_account u
            LEFT JOIN support_account_role r ON r.user_id=u.id WHERE u.id=?
            """,
            (rs, n) -> {
              if (rs.getBoolean("is_guest"))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "游客不能使用工单业务");
              return new Actor(id, SupportRole.valueOf(rs.getString("role")));
            },
            id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在");
    return rows.get(0);
  }
}
