package com.zhida.agent.support;

import static com.zhida.agent.support.SupportRole.ADMIN;
import static com.zhida.agent.support.SupportRole.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 演示数据初始化：验证只在显式开关下准备、重复执行幂等，以及四条安全停止分支。
 *
 * <p>负向用例直接调用包级的账号守卫而不是重启容器：H2 内存库名相同且 {@code DB_CLOSE_DELAY=-1}
 * 时数据会跨容器存活，靠重建上下文来隔离状态并不可靠，也会把「守卫是否正确」和「容器启动顺序」
 * 混在一起。这里直接对守卫断言，意图更清楚。
 */
@SpringBootTest(
    properties = {
      "zhida.support.enabled=true",
      "zhida.support.demo-data.enabled=true",
      "zhida.support.demo-data.password=isolated-demo-password",
      "zhida.auth.enabled=true",
      "zhida.auth.secret=isolated-demo-jwt-secret-32-bytes-long",
      "zhida.persistence.enabled=true",
      "zhida.persistence.url=jdbc:h2:mem:support-demo-data;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "zhida.persistence.username=sa",
      "zhida.persistence.password=",
      "zhida.ai.enabled=false",
      "zhida.rag.embedding-base-url=",
      "zhida.rag.embedding-api-key=",
      "zhida.rag.embedding-model=",
      "zhida.rag.upload-dir=target/support-demo-data/uploads",
      "zhida.rag.vector-store-file=target/support-demo-data/vector.json"
    })
class SupportDemoDataConfigurationTest {

  private static final String DEMO_PASSWORD = "isolated-demo-password";
  private static final PasswordEncoder PASSWORDS = new BCryptPasswordEncoder(4);

  @Autowired SupportTicketRepository repository;

  @Autowired
  @Qualifier("supportDemoDataInitializer")
  ApplicationRunner initializer;

  @Test
  void preparesPaidNotActivatedScenarioIdempotently() throws Exception {
    JdbcTemplate jdbc = repository.jdbc();
    assertDemoCounts(jdbc);

    initializer.run(new DefaultApplicationArguments(new String[0]));

    assertDemoCounts(jdbc);
    assertThat(
            jdbc.queryForMap(
                "SELECT payment_status,service_status,simulated FROM support_order"
                    + " WHERE request_id='demo-paid-not-activated-v1'"))
        .containsEntry("PAYMENT_STATUS", "PAID")
        .containsEntry("SERVICE_STATUS", "NOT_ACTIVATED")
        .containsEntry("SIMULATED", true);
  }

  /** 密码不合法必须在任何写库之前失败，否则会用空口令创建可登录账号。 */
  @Test
  void rejectsMissingOrTooShortDemoPassword() {
    ApplicationRunner missing =
        new SupportDemoDataConfiguration()
            .supportDemoDataInitializer(
                repository,
                new SupportActorResolver(repository.jdbc()),
                ticketService(),
                orderService(),
                PASSWORDS,
                "");
    ApplicationRunner tooShort =
        new SupportDemoDataConfiguration()
            .supportDemoDataInitializer(
                repository,
                new SupportActorResolver(repository.jdbc()),
                ticketService(),
                orderService(),
                PASSWORDS,
                "short");

    assertThatThrownBy(() -> missing.run(new DefaultApplicationArguments(new String[0])))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ZHIDA_SUPPORT_DEMO_DATA_PASSWORD");
    assertThatThrownBy(() -> tooShort.run(new DefaultApplicationArguments(new String[0])))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ZHIDA_SUPPORT_DEMO_DATA_PASSWORD");
    assertThat(count("SELECT COUNT(*) FROM user_account WHERE username=?", uniqueName())).isZero();
  }

  /** 同名但非本配置创建的账号必须让初始化停止，而不是被接管或改名。 */
  @Test
  void stopsWhenUsernameExistsWithAnotherId() {
    JdbcTemplate jdbc = repository.jdbc();
    String username = uniqueName();
    String foreignId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO user_account(id,username,password_hash,is_guest,created_at)"
            + " VALUES (?,?,?,false,CURRENT_TIMESTAMP)",
        foreignId,
        username,
        "FOREIGN-HASH");

    assertThatThrownBy(
            () ->
                SupportDemoDataConfiguration.ensureDemoAccount(
                    jdbc, PASSWORDS, username, USER, DEMO_PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("演示用户名已被现有账号占用");

    // 已有账号必须保持原样：ID、口令哈希和角色都不被改写。
    assertThat(jdbc.queryForObject("SELECT id FROM user_account WHERE username=?", String.class, username))
        .isEqualTo(foreignId);
    assertThat(jdbc.queryForObject("SELECT password_hash FROM user_account WHERE id=?", String.class, foreignId))
        .isEqualTo("FOREIGN-HASH");
    assertThat(count("SELECT COUNT(*) FROM support_account_role WHERE user_id=?", foreignId)).isZero();
  }

  /** 演示账号的确定性 ID 已被别的账号占用时也必须停止。 */
  @Test
  void stopsWhenDeterministicIdIsTaken() {
    JdbcTemplate jdbc = repository.jdbc();
    String username = uniqueName();
    String demoId = deterministicId(username);
    String otherName = "other_" + username;
    jdbc.update(
        "INSERT INTO user_account(id,username,password_hash,is_guest,created_at)"
            + " VALUES (?,?,?,false,CURRENT_TIMESTAMP)",
        demoId,
        otherName,
        "OTHER-HASH");

    assertThatThrownBy(
            () ->
                SupportDemoDataConfiguration.ensureDemoAccount(
                    jdbc, PASSWORDS, username, USER, DEMO_PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("演示账号 ID 已被占用");
    assertThat(jdbc.queryForObject("SELECT username FROM user_account WHERE id=?", String.class, demoId))
        .isEqualTo(otherName);
  }

  /** 演示账号角色被人工改过时停止，绝不按期望角色覆盖回去。 */
  @Test
  void stopsWhenDemoRoleWasChanged() {
    JdbcTemplate jdbc = repository.jdbc();
    String username = uniqueName();
    String demoId = deterministicId(username);
    jdbc.update(
        "INSERT INTO user_account(id,username,password_hash,is_guest,created_at)"
            + " VALUES (?,?,?,false,CURRENT_TIMESTAMP)",
        demoId,
        username,
        "EXISTING-HASH");
    jdbc.update("INSERT INTO support_account_role(user_id,role) VALUES (?,?)", demoId, ADMIN.name());

    assertThatThrownBy(
            () ->
                SupportDemoDataConfiguration.ensureDemoAccount(
                    jdbc, PASSWORDS, username, USER, DEMO_PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("演示账号角色已被修改");
    assertThat(jdbc.queryForObject("SELECT role FROM support_account_role WHERE user_id=?", String.class, demoId))
        .isEqualTo(ADMIN.name());
  }

  private SupportTicketService ticketService() {
    return new SupportTicketService(
        repository, new SupportActorResolver(repository.jdbc()), orderService());
  }

  private ProductOrderService orderService() {
    return new ProductOrderService(
        new ProductOrderRepository(repository.jdbc(), repository.transaction()),
        new SupportActorResolver(repository.jdbc()));
  }

  /** 每次用不同用户名，避免与依赖容器启动的初始化用例或历史数据相互影响。 */
  private static String uniqueName() {
    return "demo_probe_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private Integer count(String sql, Object... args) {
    return repository.jdbc().queryForObject(sql, Integer.class, args);
  }

  private static String deterministicId(String username) {
    return UUID.nameUUIDFromBytes(
            ("zhida-support-demo:" + username).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private void assertDemoCounts(JdbcTemplate jdbc) {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE username IN (?,?,?)",
                Integer.class,
                SupportDemoDataConfiguration.USERNAME,
                SupportDemoDataConfiguration.AGENT_USERNAME,
                SupportDemoDataConfiguration.ADMIN_USERNAME))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_category WHERE name=?",
                Integer.class,
                SupportDemoDataConfiguration.CATEGORY_NAME))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_product WHERE sku=?",
                Integer.class,
                SupportDemoDataConfiguration.PRODUCT_SKU))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_order WHERE request_id='demo-paid-not-activated-v1'",
                Integer.class))
        .isOne();
  }
}
