-- 独立角色表复用现有账号；没有记录的账号由服务端按 USER 处理，注册不能提权。
CREATE TABLE IF NOT EXISTS support_account_role (
    user_id VARCHAR(36) PRIMARY KEY,
    role VARCHAR(24) NOT NULL,
    CONSTRAINT ck_support_role CHECK (role IN ('USER','CUSTOMER_SERVICE','ADMIN')),
    CONSTRAINT fk_support_role_user FOREIGN KEY (user_id) REFERENCES user_account(id)
);
CREATE TABLE IF NOT EXISTS support_category (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS support_category_event (
    id VARCHAR(36) PRIMARY KEY,
    category_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    old_name VARCHAR(80),
    new_name VARCHAR(80) NOT NULL,
    old_enabled BOOLEAN,
    new_enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE (category_id,version),
    FOREIGN KEY (category_id) REFERENCES support_category(id),
    FOREIGN KEY (actor_id) REFERENCES user_account(id)
);
-- 产品与订单都是本平台的虚构演示数据，不承载真实支付或真实服务开通写入。
CREATE TABLE IF NOT EXISTS support_product (
    id VARCHAR(36) PRIMARY KEY,
    sku VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    simulated BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ck_support_product_simulated CHECK (simulated=true)
);
CREATE TABLE IF NOT EXISTS support_product_event (
    product_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (product_id,version),
    FOREIGN KEY (product_id) REFERENCES support_product(id),
    FOREIGN KEY (actor_id) REFERENCES user_account(id)
);
-- created_by + request_id 防止管理员重试时重复生成演示订单；状态组合由 CHECK 和服务层双重校验。
CREATE TABLE IF NOT EXISTS support_order (
    id VARCHAR(36) PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    user_id VARCHAR(36) NOT NULL,
    product_id VARCHAR(36) NOT NULL,
    payment_status VARCHAR(24) NOT NULL,
    service_status VARCHAR(24) NOT NULL,
    simulated BOOLEAN NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    paid_at TIMESTAMP(6),
    activated_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE (created_by,request_id),
    CONSTRAINT ck_support_order_payment CHECK (payment_status IN ('PENDING_PAYMENT','PAID')),
    CONSTRAINT ck_support_order_service CHECK (service_status IN ('NOT_ACTIVATED','ACTIVATED')),
    CONSTRAINT ck_support_order_state CHECK (
        (payment_status='PENDING_PAYMENT' AND service_status='NOT_ACTIVATED') OR payment_status='PAID'
    ),
    CONSTRAINT ck_support_order_simulated CHECK (simulated=true),
    CONSTRAINT fk_support_order_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_support_order_product FOREIGN KEY (product_id) REFERENCES support_product(id),
    CONSTRAINT fk_support_order_creator FOREIGN KEY (created_by) REFERENCES user_account(id)
);
CREATE TABLE IF NOT EXISTS support_order_event (
    order_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    payment_status VARCHAR(24) NOT NULL,
    service_status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (order_id,version),
    FOREIGN KEY (order_id) REFERENCES support_order(id),
    FOREIGN KEY (actor_id) REFERENCES user_account(id)
);
-- request_id 按用户唯一，request_hash 绑定规范化内容；version 用于条件更新防覆盖。
-- 列表组合索引由 SupportConfiguration 幂等补建，已有同名索引不会自动替换。
CREATE TABLE IF NOT EXISTS support_ticket (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    title VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    category_id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36),
    status VARCHAR(24) NOT NULL,
    assigned_to VARCHAR(36),
    solution TEXT,
    rating INT,
    evaluation VARCHAR(1000),
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE (user_id,request_id),
    CONSTRAINT ck_ticket_status CHECK (status IN ('PENDING','PROCESSING','AWAITING_CONFIRMATION','CLOSED')),
    CONSTRAINT ck_ticket_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    FOREIGN KEY (user_id) REFERENCES user_account(id),
    FOREIGN KEY (assigned_to) REFERENCES user_account(id),
    FOREIGN KEY (category_id) REFERENCES support_category(id),
    CONSTRAINT fk_support_ticket_order FOREIGN KEY (order_id) REFERENCES support_order(id)
);
-- 所有业务变更都有一个版本事件；创建为版本 0，状态不变的补充/回复也要记录。
CREATE TABLE IF NOT EXISTS support_ticket_event (
    ticket_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    actor_role VARCHAR(24) NOT NULL,
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    previous_assignee VARCHAR(36),
    assigned_to VARCHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (ticket_id,version),
    FOREIGN KEY (ticket_id) REFERENCES support_ticket(id),
    FOREIGN KEY (actor_id) REFERENCES user_account(id)
);
-- 回复引用对应版本的事件，工单变更、事件、回复必须在同一事务提交。
CREATE TABLE IF NOT EXISTS support_ticket_reply (
    id VARCHAR(36) PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE (ticket_id,version),
    FOREIGN KEY (ticket_id,version) REFERENCES support_ticket_event(ticket_id,version),
    FOREIGN KEY (actor_id) REFERENCES user_account(id)
);
