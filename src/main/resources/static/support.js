/*
 * 知答售后工作台前端。
 *
 * 设计要点：
 * 1. 身份完全来自服务端：前端只保存 JWT，角色由 /api/v1/support/me 返回；
 *    页面隐藏按钮只是体验优化，真正的权限判断仍在后端，被拒绝时按接口返回的状态码提示。
 * 2. 对已存在工单的变更都携带 expectedVersion：工单被其他人改动后服务端返回 409，页面重新加载而不是盲目重试。
 *    建单、分类维护和录入模拟订单是新增资源，没有版本字段，改用 requestId 幂等。
 * 3. 售后助手只产生「未确认草稿」：草稿必须由用户在本页明确确认，才会调用建单接口。
 *    提交前先用 /ticket-drafts/{requestId} 检查是否已经建单，避免重复。
 * 4. 工单详情的状态脊线把状态流转与审计事件放在同一条时间轴上，这是本页的主要视觉信息。
 */

const TOKEN_KEY = 'zhida-token';
const STATUS_LABELS = Object.freeze({
    PENDING: '待受理',
    PROCESSING: '处理中',
    AWAITING_CONFIRMATION: '待用户确认',
    CLOSED: '已关闭'
});
const STATUS_FLOW = ['PENDING', 'PROCESSING', 'AWAITING_CONFIRMATION', 'CLOSED'];
const ACTION_LABELS = Object.freeze({
    CREATED: '创建工单',
    COMMENT: '补充说明',
    CLAIM: '客服接单',
    REPLY: '客服回复',
    SOLUTION: '提交解决方案',
    REOPEN: '用户退回',
    CONFIRM: '用户确认解决',
    ASSIGN: '分配客服'
});
const ROLE_LABELS = Object.freeze({
    USER: '普通用户',
    CUSTOMER_SERVICE: '客服',
    ADMIN: '管理员'
});

const el = id => document.getElementById(id);
const shell = el('shell');
const view = el('view');

let token = sessionStorage.getItem(TOKEN_KEY);
let me = null;
let health = null;
let categories = [];
let currentTicketId = null;
/**
 * 渲染序号：dispatch 会因初始化与 hashchange 并发执行，只有最新一次可以写视图。
 * 注意这里不做「同 hash 去重」——POST 之后刷新当前页、按钮点击重载都依赖同 hash 重新渲染，
 * 去重会把这类真实需求误判成重复触发。
 */
let dispatchToken = 0;
let pendingDraft = null;
let assistantConversationId = null;
let assistantBusy = false;
let selectedKnowledgeBaseId = null;

/* ---------------- 基础请求 ---------------- */

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (token) headers.set('Authorization', `Bearer ${token}`);
    if (options.body && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json; charset=utf-8');
    }
    const response = await window.fetch(path, { ...options, headers });
    if (response.status === 401) {
        signOut('登录状态已过期，请重新登录。');
        throw new ApiError(401, '登录状态已过期，请重新登录。');
    }
    return response;
}

class ApiError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

/** 读取接口错误信息；后端用 ResponseStatusException 时正文可能为空，需要回退到通用文案。 */
async function readError(response, fallback) {
    const text = await response.text().catch(() => '');
    if (text) {
        try {
            const parsed = JSON.parse(text);
            if (parsed.message) return parsed.message;
            if (parsed.error) return parsed.error;
        } catch (_) {
            return text.slice(0, 200);
        }
    }
    return fallback;
}

async function getJson(path) {
    const response = await api(path);
    if (response.status === 204) return null;
    if (!response.ok) throw new ApiError(response.status, await readError(response, `请求失败（${response.status}）`));
    return response.json();
}

async function postJson(path, body) {
    const response = await api(path, { method: 'POST', body: JSON.stringify(body ?? {}) });
    if (!response.ok) throw new ApiError(response.status, await readError(response, `操作失败（${response.status}）`));
    return response.status === 204 ? null : response.json();
}

async function putJson(path, body) {
    const response = await api(path, { method: 'PUT', body: JSON.stringify(body ?? {}) });
    if (!response.ok) throw new ApiError(response.status, await readError(response, `操作失败（${response.status}）`));
    return response.status === 204 ? null : response.json();
}

/* ---------------- 小工具 ---------------- */

function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, char => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[char]);
}

function statusTag(status) {
    return `<span class="status-tag status-${status}">${STATUS_LABELS[status] || status}</span>`;
}

function formatTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '—';
    const pad = number => String(number).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

let toastTimer;
function toast(message, isError = false) {
    const node = el('toast');
    node.textContent = message;
    node.classList.toggle('error', isError);
    node.hidden = false;
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => { node.hidden = true; }, 3600);
}

function setBusy(button, busy, label) {
    if (!button) return;
    if (busy) {
        if (!button.dataset.idleLabel) button.dataset.idleLabel = button.textContent;
        button.disabled = true;
        button.textContent = label || '处理中…';
    } else {
        button.disabled = false;
        if (button.dataset.idleLabel) button.textContent = button.dataset.idleLabel;
    }
}

/* ---------------- 登录与身份 ---------------- */

function showAuth(message, isError) {
    const modal = el('authModal');
    modal.hidden = false;
    modal.setAttribute('aria-hidden', 'false');
    shell.setAttribute('inert', '');
    shell.setAttribute('aria-hidden', 'true');
    const status = el('authStatus');
    status.textContent = message || '请使用已有账号登录。';
    status.classList.toggle('error', Boolean(isError));
    window.setTimeout(() => el('username').focus(), 30);
}

function hideAuth() {
    const modal = el('authModal');
    modal.hidden = true;
    modal.setAttribute('aria-hidden', 'true');
    shell.removeAttribute('inert');
    shell.removeAttribute('aria-hidden');
}

function signOut(message) {
    token = null;
    me = null;
    sessionStorage.removeItem(TOKEN_KEY);
    // 登出必须清掉待确认草稿并关闭弹窗：草稿属于上一个账号，残留会让下一个登录者看到并确认它。
    pendingDraft = null;
    closeDraftModal();
    if (el('draftCompose')) el('draftCompose').hidden = true;
    if (el('viewDraft')) el('viewDraft').hidden = true;
    // 文档轮询同样属于上一个账号的页面，必须停止。
    stopDocumentPolling();
    // 对话上下文同样属于上一个账号，必须清空，否则下一个账号会看到上一段问答被当成草稿描述。
    assistantConversationId = null;
    lastAssistantQuestion = '';
    lastAssistantAnswer = '';
    el('accountName').textContent = '未登录';
    el('accountRole').textContent = '—';
    el('accountHint').textContent = '登录后查看售后内容';
    el('nav').innerHTML = '';
    el('brandRole').textContent = '工作台';
    showAuth(message || '已退出登录。', false);
}

async function login(username, password, register) {
    const path = register ? '/api/v1/auth/register' : '/api/v1/auth/login';
    const response = await window.fetch(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({ username, password })
    });
    if (!response.ok) {
        if (response.status === 409) throw new ApiError(409, '用户名已存在，请直接登录。');
        if (response.status === 401) throw new ApiError(401, '用户名或密码不正确。');
        if (response.status === 429) throw new ApiError(429, '操作过于频繁，请稍后再试。');
        if (response.status === 400) throw new ApiError(400, '用户名需为 3～32 位字母、数字或下划线，密码至少 8 个字符。');
        throw new ApiError(response.status, `登录失败（${response.status}）`);
    }
    const payload = await response.json();
    token = payload.accessToken;
    sessionStorage.setItem(TOKEN_KEY, token);
}

/** 角色由后端数据库解析；游客会被后端拒绝，前端据此提示不能使用售后模块。 */
async function loadIdentity() {
    const response = await api('/api/v1/support/me');
    if (response.status === 403) {
        const message = await readError(response, '当前账号不能使用售后模块。');
        throw new ApiError(403, message);
    }
    if (!response.ok) throw new ApiError(response.status, await readError(response, '无法读取售后身份。'));
    me = await response.json();
}

function renderAccount() {
    const label = ROLE_LABELS[me.role] || me.role;
    el('accountName').textContent = label + '账号';
    el('accountRole').textContent = label;
    el('brandRole').textContent = label + '工作台';
    el('accountHint').textContent = `工号 ${me.id.slice(0, 8)}`;
}

/* ---------------- 导航与路由 ---------------- */

const ROUTES = {
    overview: { title: '售后工作台', subtitle: '从这里开始解决售后问题', render: renderOverview },
    assistant: { title: '售后助手', subtitle: '描述问题，助手查询订单与知识库后给出建议', render: renderAssistant, chat: true },
    orders: { title: '我的订单', subtitle: '核对模拟订单的付款与服务开通状态', render: renderOrders },
    newTicket: { title: '手动创建工单', subtitle: '模型不可用时也能直接提交售后问题', render: renderManualTicket },
    tickets: { title: '我的工单', subtitle: '查看和跟进你提交的售后问题', render: renderTicketList },
    ticket: { title: '工单详情', subtitle: '处理记录、回复与状态变更', render: renderTicketDetail },
    pending: { title: '待受理队列', subtitle: '尚未有人接单的工单', render: () => renderTicketList({ view: 'pending' }) },
    assigned: { title: '我的处理工单', subtitle: '由你接单或已分配给你的工单', render: () => renderTicketList({ view: 'mine' }) },
    categories: { title: '分类管理', subtitle: '维护售后问题分类及其启用状态', render: renderCategories },
    assignment: { title: '工单分配', subtitle: '把工单指派给客服并查看全部记录', render: renderAssignment },
    catalog: { title: '产品与模拟订单', subtitle: '维护虚构产品与演示订单', render: renderCatalog },
    knowledge: { title: '知识库管理', subtitle: '维护公共售后资料与本人私人文档', render: renderKnowledge }
};

function navItems() {
    if (me.role === 'ADMIN') {
        return [
            { group: '工作台', items: [['overview', '总览']] },
            { group: '工单', items: [['assignment', '工单分配'], ['tickets', '全部工单']] },
            { group: '配置', items: [['categories', '分类管理'], ['catalog', '产品与订单'], ['knowledge', '知识库']] }
        ];
    }
    if (me.role === 'CUSTOMER_SERVICE') {
        return [
            { group: '工作台', items: [['overview', '总览']] },
            { group: '工单', items: [['pending', '待受理队列'], ['assigned', '我的处理工单']] }
        ];
    }
    return [
        { group: '解决问题', items: [['assistant', '售后助手'], ['orders', '我的订单'], ['newTicket', '手动建单'], ['tickets', '我的工单']] },
        { group: '资料', items: [['knowledge', '我的资料']] }
    ];
}

function renderNav(counts = {}) {
    const html = navItems().map(group => `
        <div class="nav-group">${escapeHtml(group.group)}</div>
        ${group.items.map(([route, label]) => {
            const count = counts[route];
            const badge = typeof count === 'number' ? `<span class="nav-count">${count}</span>` : '';
            return `<button class="nav-item" type="button" data-route="${route}">${escapeHtml(label)}${badge}</button>`;
        }).join('')}
    `).join('');
    el('nav').innerHTML = html;
    el('nav').querySelectorAll('[data-route]').forEach(button => {
        button.addEventListener('click', () => {
            shell.classList.remove('nav-open');
            navigate(button.dataset.route);
        });
    });
}

function currentRoute() {
    const hash = window.location.hash.replace('#', '');
    if (hash.startsWith('ticket/')) return 'ticket';
    return ROUTES[hash] ? hash : 'overview';
}

function currentTicketFromHash() {
    const hash = window.location.hash.replace('#', '');
    return hash.startsWith('ticket/') ? decodeURIComponent(hash.slice(7)) : null;
}

function navigate(route, param) {
    // 无权路由直接落到总览，避免先渲染「正在加载…」再被 dispatch 弹回。
    // 注意：这里保持「改 hash 由 hashchange 触发渲染」的简单模型，不额外调用 dispatch，
    // 也不替换地址栏方式，历史上此类改动（replace + 主动 dispatch）反而会减少导航历史条目。
    if (route !== 'ticket' && route !== 'overview' && me && !routeAllowed(route)) {
        route = 'overview';
        param = undefined;
    }
    const target = param ? `${route}/${encodeURIComponent(param)}` : route;
    if (window.location.hash === `#${target}`) {
        dispatch();
        return;
    }
    window.location.hash = `#${target}`;
}

function markActiveNav(route) {
    const active = route === 'ticket' ? (me.role === 'ADMIN' ? 'assignment' : me.role === 'CUSTOMER_SERVICE' ? 'assigned' : 'tickets') : route;
    el('nav').querySelectorAll('[data-route]').forEach(button => {
        if (button.dataset.route === active) button.setAttribute('aria-current', 'page');
        else button.removeAttribute('aria-current');
    });
}

async function dispatch() {
    if (!me) return;
    // 每次渲染都取一个递增序号：页面初始化（start）和 hashchange 会并发调用 dispatch，
    // 较早发起的那次可能后返回并覆盖新页面。只有最新一次允许写 DOM，避免显示过期工单状态。
    // 需要与模块级 JWT 变量 token 区分，所以这里命名为 renderSeq。
    const renderSeq = ++dispatchToken;
    const route = currentRoute();
    const config = ROUTES[route];
    if (!routeAllowed(route)) {
        // 无权路由：跳总览并结束本次渲染，重定向后的渲染由 hashchange 触发。
        navigate('overview');
        return;
    }
    currentTicketId = route === 'ticket' ? currentTicketFromHash() : null;
    // 切路由时关闭草稿弹窗与造草稿表单：它们都是跨路由的固定定位层，残留会挡住新页面的点击，
    // 也会让用户在下一条路由上看到上一页的草稿。已生成的草稿保留，可用「查看草稿」重新打开。
    closeDraftModal();
    if (el('draftCompose')) el('draftCompose').hidden = true;
    // 离开知识库页面就停止文档轮询，避免在别的页面上继续发请求。
    stopDocumentPolling();
    el('viewTitle').textContent = config.title;
    el('viewSubtitle').textContent = config.subtitle;
    markActiveNav(route);
    view.className = config.chat ? 'view wide' : 'view';
    // 在视图根元素上标记本次渲染序号；各 render* 内部 await 之后的写入都要重新确认序号，
    // 否则「A 路由请求慢、B 路由已渲染完成」时会出现标题是 B、正文是 A 的混合页面。
    view.dataset.renderSeq = String(renderSeq);
    view.innerHTML = '<p class="loading">正在加载…</p>';
    try {
        const counts = await loadCounts();
        if (!isCurrentRender(renderSeq)) return;
        renderNav(counts);
        markActiveNav(route);
        await config.render();
    } catch (error) {
        if (!isCurrentRender(renderSeq)) return;
        // 401 表示会话已经失效，登录弹窗会盖住视图；但这里仍要清掉「正在加载…」，
        // 否则用户关闭弹窗或弹窗未显示时会看到永久加载中的空白页。
        if (error.status === 401) {
            view.innerHTML = '<div class="view-inner"><div class="empty"><strong>登录状态已失效</strong>请重新登录后再继续。</div></div>';
            return;
        }
        view.innerHTML = `<div class="view-inner"><div class="empty"><strong>加载失败</strong>${escapeHtml(error.message)}</div></div>`;
    }
}

/**
 * 视图写入守卫。不传参数时比较「当前渲染序号」与「视图根元素记录的序号」，供各 render* 在
 * await 之后、写 innerHTML 之前调用；dispatch 内部则带上自己的序号做一次早退判断。
 */
function isCurrentRender(renderSeq = dispatchToken) {
    return renderSeq === dispatchToken && view.dataset.renderSeq === String(renderSeq);
}

/**
 * 路由可见性只改善页面体验；即使直接调用接口，后端仍会按数据库角色重新授权。
 *
 * <p>角色判断必须与 {@link navItems} 保持一致：两处都只把 USER 当作已知角色，
 * 未知角色（例如后端将来新增角色）按最小可见性处理，不能在这里兜底成管理员路由。
 */
function routeAllowed(route) {
    if (route === 'ticket' || route === 'overview') return true;
    if (me.role === 'ADMIN') return ['assignment', 'tickets', 'categories', 'catalog', 'knowledge'].includes(route);
    if (me.role === 'CUSTOMER_SERVICE') return ['pending', 'assigned'].includes(route);
    return ['assistant', 'orders', 'newTicket', 'tickets', 'knowledge'].includes(route);
}

/** 导航计数只使用当前角色有权访问的接口；失败时静默返回空计数，不影响主视图。 */
async function loadCounts() {
    const counts = {};
    try {
        if (me.role === 'CUSTOMER_SERVICE') {
            const pending = await getJson('/api/v1/support/tickets?view=pending');
            const mine = await getJson('/api/v1/support/tickets?view=mine');
            counts.pending = pending.length;
            counts.assigned = mine.length;
        } else if (me.role === 'ADMIN') {
            const all = await getJson('/api/v1/support/tickets?view=all');
            counts.assignment = all.length;
            counts.tickets = all.length;
        } else {
            const mine = await getJson('/api/v1/support/tickets?view=mine');
            counts.tickets = mine.length;
        }
    } catch (_) { /* 计数失败不影响功能 */ }
    return counts;
}

/* ---------------- 总览 ---------------- */

async function renderOverview() {
    const role = me.role;
    const title = role === 'ADMIN'
        ? '管理售后分类、分配工单，并维护公共产品资料'
        : role === 'CUSTOMER_SERVICE'
            ? '查看待受理队列，接单后回复并提交解决方案'
            : '描述你的问题，助手会先查订单和资料，再决定是否需要人工处理';

    const quick = role === 'USER'
        ? [['assistant', '描述我的问题'], ['tickets', '查看处理进度']]
        : role === 'CUSTOMER_SERVICE'
            ? [['pending', '进入待受理队列'], ['assigned', '查看我的工单']]
            : [['assignment', '分配工单'], ['categories', '管理分类']];

    let counts = {};
    try {
        if (role === 'CUSTOMER_SERVICE') {
            counts = {
                待受理: (await getJson('/api/v1/support/tickets?view=pending')).length,
                处理中: (await getJson('/api/v1/support/tickets?view=mine')).filter(t => t.status === 'PROCESSING').length
            };
        } else if (role === 'ADMIN') {
            const all = await getJson('/api/v1/support/tickets?view=all');
            counts = {
                全部工单: all.length,
                待受理: all.filter(t => t.status === 'PENDING').length,
                待用户确认: all.filter(t => t.status === 'AWAITING_CONFIRMATION').length
            };
        } else {
            const mine = await getJson('/api/v1/support/tickets?view=mine');
            counts = {
                我的工单: mine.length,
                进行中: mine.filter(t => t.status !== 'CLOSED').length,
                待我确认: mine.filter(t => t.status === 'AWAITING_CONFIRMATION').length
            };
        }
    } catch (_) { /* 计数失败不影响入口 */ }

    const facts = Object.entries(counts)
        .map(([label, value]) => `<div><dt>${escapeHtml(label)}</dt><dd>${value}</dd></div>`)
        .join('');

    if (!isCurrentRender()) return;
    view.innerHTML = `
        <div class="view-inner">
            <div class="section-head">
                <div><h2>${escapeHtml(title)}</h2><p>演示数据均为虚构：产品、订单和知识库文档都不代表真实商品。</p></div>
            </div>
            ${facts ? `<div class="panel"><h3>当前进度</h3><dl class="fact-grid">${facts}</dl></div>` : ''}
            <div class="panel">
                <h3>接下来</h3>
                <div class="inline-fields" style="flex-wrap: wrap">
                    ${quick.map(([route, label]) => `<button class="button ${route === quick[0][0] ? 'primary' : 'secondary'}" type="button" data-go="${route}">${escapeHtml(label)}</button>`).join('')}
                </div>
                <p class="panel-description" style="margin-top:12px">
                    ${role === 'USER'
                        ? '助手给出的建议会标注来源；资料不足时会明确说明，并引导你创建工单。'
                        : role === 'CUSTOMER_SERVICE'
                            ? '接单后只有你能回复和提交方案；方案提交后等待用户确认，用户退回则回到处理中。'
                            : '管理员可以分类、分配工单，并维护公共售后知识库；不能替用户确认关闭工单。'}
                </p>
            </div>
        </div>`;
    view.querySelectorAll('[data-go]').forEach(button => {
        button.addEventListener('click', () => navigate(button.dataset.go));
    });
}

/* ---------------- 工单列表 ---------------- */

async function renderTicketList(options = {}) {
    const query = options.view || (me.role === 'CUSTOMER_SERVICE' ? 'mine' : me.role === 'ADMIN' ? 'all' : 'mine');
    const tickets = await getJson(`/api/v1/support/tickets?view=${query}`);
    const rows = tickets.map(ticket => `
        <button class="ticket-row" type="button" data-ticket="${escapeHtml(ticket.id)}">
            <span class="ticket-row-title">${escapeHtml(ticket.title)}</span>
            <span class="ticket-row-side">${statusTag(ticket.status)}</span>
            <span class="ticket-row-meta">
                <span class="mono">${escapeHtml(ticket.id.slice(0, 8))}</span>
                <span>更新于 ${formatTime(ticket.updatedAt)}</span>
                <span>版本 ${ticket.version}</span>
                ${ticket.orderId ? `<span class="mono">订单 ${escapeHtml(ticket.orderId.slice(0, 8))}</span>` : ''}
            </span>
        </button>
    `).join('');

    const emptyText = query === 'pending'
        ? ['暂时没有待受理工单', '新工单提交后会出现在这里。']
        : query === 'all'
            ? ['还没有任何工单', '用户提交售后问题后会显示在这里。']
            : ['你还没有工单', me.role === 'USER' ? '可以在售后助手里描述问题，确认后创建工单。' : '接单后工单会出现在这里。'];

    if (!isCurrentRender()) return;
    view.innerHTML = `
        <div class="view-inner">
            <div class="section-head">
                <div><h2>${query === 'pending' ? '待受理队列' : query === 'all' ? '全部工单' : query === 'mine' && me.role === 'CUSTOMER_SERVICE' ? '我的处理工单' : '我的工单'}</h2>
                <p>共 ${tickets.length} 张，按最近更新排序。</p></div>
                ${me.role === 'USER' ? '<button class="button primary" type="button" data-go="assistant">用助手描述问题</button>' : ''}
            </div>
            ${tickets.length
                ? `<div class="ticket-list">${rows}</div>`
                : `<div class="empty"><strong>${emptyText[0]}</strong>${emptyText[1]}</div>`}
        </div>`;

    view.querySelectorAll('[data-ticket]').forEach(button => {
        button.addEventListener('click', () => navigate('ticket', button.dataset.ticket));
    });
    view.querySelectorAll('[data-go]').forEach(button => {
        button.addEventListener('click', () => navigate(button.dataset.go));
    });
}

/* ---------------- 用户端：订单与手动建单 ---------------- */

async function renderOrders() {
    const orders = await getJson('/api/v1/support/orders');
    if (!isCurrentRender()) return;
    view.innerHTML = `
        <div class="view-inner">
            <div class="section-head"><div><h2>我的模拟订单</h2>
                <p>这里只展示当前账号的虚构订单。AI 和页面都不能修改付款、退款或开通状态。</p></div>
                <button class="button primary" type="button" data-go="assistant">咨询售后助手</button>
            </div>
            ${orders.length ? `<div class="ticket-list">${orders.map(order => `
                <article class="ticket-row static-row">
                    <span class="ticket-row-title">${escapeHtml(order.productName)}</span>
                    <span class="ticket-row-side"><span class="status-tag ${order.serviceStatus === 'ACTIVATED' ? 'status-closed' : 'status-pending'}">${order.serviceStatus === 'ACTIVATED' ? '已开通' : '未开通'}</span></span>
                    <span class="ticket-row-meta"><span class="mono">${escapeHtml(order.orderNo)}</span><span>${order.paymentStatus === 'PAID' ? '已付款' : '待付款'}</span><span>模拟数据</span></span>
                </article>`).join('')}</div>`
                : '<div class="empty"><strong>还没有模拟订单</strong>请让演示管理员准备虚构订单，或直接手动创建不关联订单的工单。</div>'}
        </div>`;
    view.querySelector('[data-go]').addEventListener('click', () => navigate('assistant'));
}

async function renderManualTicket() {
    const [orders, enabledCategories] = await Promise.all([
        getJson('/api/v1/support/orders'),
        getJson('/api/v1/support/categories')
    ]);
    const requestId = crypto.randomUUID();
    if (!isCurrentRender()) return;
    view.innerHTML = `
        <div class="view-inner narrow-content">
            <div class="section-head"><div><h2>手动创建工单</h2>
                <p>该流程不依赖大模型。点击确认后，服务端会再次校验分类、订单归属与 requestId。</p></div></div>
            <div class="panel">
                ${enabledCategories.length ? `<form id="manualTicketForm">
                    <div class="field"><label class="field-label" for="manualTitle">标题</label><input id="manualTitle" maxlength="120" required placeholder="例如：订单已付款但服务未开通"></div>
                    <div class="field"><label class="field-label" for="manualCategory">问题分类</label><select id="manualCategory" required>${enabledCategories.map(category => `<option value="${escapeHtml(category.id)}">${escapeHtml(category.name)}</option>`).join('')}</select></div>
                    <div class="field"><label class="field-label" for="manualOrder">关联订单（可选）</label><select id="manualOrder"><option value="">不关联订单</option>${orders.map(order => `<option value="${escapeHtml(order.id)}">${escapeHtml(order.orderNo)} · ${escapeHtml(order.productName)} · ${order.paymentStatus === 'PAID' ? '已付款' : '待付款'} / ${order.serviceStatus === 'ACTIVATED' ? '已开通' : '未开通'}</option>`).join('')}</select></div>
                    <div class="field"><label class="field-label" for="manualDescription">问题描述</label><textarea id="manualDescription" maxlength="4000" required placeholder="请描述现象、发生时间和已经尝试过的操作。"></textarea></div>
                    <p class="panel-description">提交按钮代表你明确确认创建工单；重复点击会使用同一 requestId，不会重复建单。</p>
                    <button class="button primary" type="submit">确认创建工单</button>
                </form>` : '<div class="empty"><strong>暂时无法建单</strong>当前没有启用的售后分类，请联系演示管理员先创建分类。</div>'}
            </div>
        </div>`;
    const form = el('manualTicketForm');
    if (!form) return;
    form.addEventListener('submit', async event => {
        event.preventDefault();
        const button = event.submitter;
        setBusy(button, true, '正在创建…');
        try {
            const ticket = await postJson('/api/v1/support/tickets', {
                requestId,
                title: el('manualTitle').value.trim(),
                description: el('manualDescription').value.trim(),
                categoryId: el('manualCategory').value,
                orderId: el('manualOrder').value || null,
                confirmed: true
            });
            toast('工单已创建，客服会尽快处理。');
            navigate('ticket', ticket.id);
        } catch (error) {
            toast(error.message, true);
            setBusy(button, false);
        }
    });
}

/* ---------------- 工单详情 ---------------- */

async function renderTicketDetail() {
    if (!currentTicketId) {
        navigate('tickets');
        return;
    }
    const detail = await getJson(`/api/v1/support/tickets/${encodeURIComponent(currentTicketId)}`);
    const ticket = detail.ticket;
    const category = categories.find(item => item.id === ticket.categoryId);

    if (!isCurrentRender()) return;
    view.innerHTML = `
        <div class="view-inner">
            <button class="button ghost small" type="button" data-back>← 返回列表</button>
            <div class="detail-grid" style="margin-top:12px">
                <div class="detail-main">
                    <h1 class="detail-title">${escapeHtml(ticket.title)}</h1>
                    <div class="detail-facts">
                        ${statusTag(ticket.status)}
                        <span class="chip mono">工单 ${escapeHtml(ticket.id.slice(0, 8))}</span>
                        <span class="chip">版本 ${ticket.version}</span>
                        ${category ? `<span class="chip">${escapeHtml(category.name)}</span>` : ''}
                        ${ticket.orderId ? `<span class="chip mono">订单 ${escapeHtml(ticket.orderId.slice(0, 8))}</span>` : ''}
                    </div>

                    <div class="panel">
                        <h3>问题描述</h3>
                        <p class="prose">${escapeHtml(ticket.description)}</p>
                        ${ticket.solution ? `<h3 style="margin-top:16px">当前方案</h3><p class="prose">${escapeHtml(ticket.solution)}</p>` : ''}
                        ${ticket.rating ? `<h3 style="margin-top:16px">用户评价</h3><p class="prose">${ticket.rating} 分${ticket.evaluation ? ' · ' + escapeHtml(ticket.evaluation) : ''}</p>` : ''}
                    </div>

                    <div class="panel">
                        <h3>处理记录</h3>
                        ${detail.replies.length ? `<div class="thread">${detail.replies.map(reply => `
                            <article class="entry ${reply.actorId === ticket.assignedTo ? 'entry-agent' : ''}">
                                <div class="entry-head">
                                    <span class="entry-kind">${escapeHtml(ACTION_LABELS[reply.kind] || reply.kind)}</span>
                                    <span>${reply.actorId === ticket.userId ? '用户' : reply.actorId === ticket.assignedTo ? '客服' : '处理人'}</span>
                                    <span>${formatTime(reply.createdAt)}</span>
                                    <span class="mono">v${reply.version}</span>
                                </div>
                                <p class="prose">${escapeHtml(reply.content)}</p>
                            </article>`).join('')}</div>`
                        : '<p class="muted">还没有回复。补充说明、客服回复和方案都会记录在这里。</p>'}
                    </div>

                    <div id="actionPanel"></div>
                </div>

                <aside class="spine">
                    <div class="panel">
                        <h3>处理进度</h3>
                        <p class="panel-description">状态按箭头方向推进，用户未解决时可以退回处理中；关闭后不能再修改。</p>
                        <ol class="spine-steps">${spineSteps(ticket.status)}</ol>
                    </div>
                    <div class="panel">
                        <h3>状态变更记录</h3>
                        <ol class="timeline">${detail.events.map(event => `
                            <li>
                                <div class="event-line">
                                    <strong>${escapeHtml(ACTION_LABELS[event.action] || event.action)}</strong>
                                    <span class="chip mono">v${event.version}</span>
                                </div>
                                <div class="event-transition">
                                    ${event.fromStatus ? `${STATUS_LABELS[event.fromStatus]} <span class="arrow">→</span> ${STATUS_LABELS[event.toStatus]}` : STATUS_LABELS[event.toStatus] || event.toStatus}
                                    · ${ROLE_LABELS[event.actorRole] || event.actorRole} · ${formatTime(event.createdAt)}
                                </div>
                            </li>`).join('')}</ol>
                    </div>
                </aside>
            </div>
        </div>`;

    view.querySelector('[data-back]').addEventListener('click', () => navigate(me.role === 'CUSTOMER_SERVICE' ? 'assigned' : me.role === 'ADMIN' ? 'assignment' : 'tickets'));
    renderActions(ticket);
}

/** 状态脊线：已完成/当前/未到达三种节点，对应当前状态在四态流程中的位置。 */
function spineSteps(status) {
    const currentIndex = STATUS_FLOW.indexOf(status);
    return STATUS_FLOW.map((state, index) => {
        const stateAttr = index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'todo';
        const hint = stateAttr === 'done' ? '已通过' : stateAttr === 'current' ? '当前状态' : '尚未到达';
        return `<li class="spine-step" data-state="${stateAttr}">
            <strong>${STATUS_LABELS[state]}</strong>
            <small>${hint}</small>
        </li>`;
    }).join('');
}

/** 按角色、当前状态和版本渲染可用操作；服务端仍会独立校验，这里的隐藏只是体验优化。 */
function renderActions(ticket) {
    const panel = el('actionPanel');
    const version = ticket.version;
    const blocks = [];

    if (me.role === 'USER' && ticket.userId === me.id) {
        if (ticket.status === 'AWAITING_CONFIRMATION') {
            blocks.push(`
                <div class="panel">
                    <h3>方案是否解决了问题？</h3>
                    <p class="panel-description">确认解决会关闭工单并记录评价；如果还没解决，可以退回给客服继续处理。</p>
                    <div class="field"><label class="field-label" for="rateScore">评价分数（1–5）</label>
                        <select id="rateScore">${[5, 4, 3, 2, 1].map(n => `<option value="${n}">${n} 分</option>`).join('')}</select></div>
                    <div class="field"><label class="field-label" for="rateText">评价内容（可留空）</label>
                        <textarea id="rateText" maxlength="1000" placeholder="例如：按方案操作后服务已开通。"></textarea></div>
                    <div class="inline-fields">
                        <button class="button primary" type="button" data-act="confirm">确认解决并关闭</button>
                        <button class="button secondary" type="button" data-act="reopen">仍未解决，退回处理</button>
                    </div>
                </div>`);
        }
        if (ticket.status !== 'CLOSED') {
            blocks.push(`
                <div class="panel">
                    <h3>补充说明</h3>
                    <div class="field"><textarea id="commentText" maxlength="4000" placeholder="补充现象、时间或已尝试的操作。"></textarea></div>
                    <button class="button" type="button" data-act="comment">提交补充</button>
                </div>`);
        }
    }

    if (me.role === 'CUSTOMER_SERVICE') {
        if (ticket.status === 'PENDING') {
            blocks.push(`<div class="panel"><h3>接单</h3>
                <p class="panel-description">接单后工单进入处理中，只有你能回复和提交方案。多人同时接单时只有一个会成功。</p>
                <button class="button primary" type="button" data-act="claim">接单处理</button></div>`);
        }
        if (ticket.status === 'PROCESSING' && ticket.assignedTo === me.id) {
            blocks.push(`
                <div class="panel">
                    <h3>回复用户</h3>
                    <div class="field"><textarea id="replyText" maxlength="4000" placeholder="向用户说明当前排查进度。"></textarea></div>
                    <button class="button" type="button" data-act="reply">发送回复</button>
                </div>
                <div class="panel">
                    <h3>提交解决方案</h3>
                    <p class="panel-description">提交后状态变为待用户确认；用户退回会回到处理中。</p>
                    <div class="field"><textarea id="solutionText" maxlength="4000" placeholder="写明处理动作和用户需要做的事。"></textarea></div>
                    <button class="button primary" type="button" data-act="solution">提交方案</button>
                </div>`);
        }
    }

    if (me.role === 'ADMIN') {
        if (ticket.status === 'PENDING' || ticket.status === 'PROCESSING') {
            blocks.push(`
                <div class="panel">
                    <h3>分配客服</h3>
                    <p class="panel-description">分配后工单进入处理中。管理员不能替用户确认关闭工单。</p>
                    <div class="field"><label class="field-label" for="agentSelect">选择客服</label>
                        <select id="agentSelect"><option value="">正在读取客服账号…</option></select></div>
                    <button class="button primary" type="button" data-act="assign">分配给该客服</button>
                </div>`);
        }
    }

    panel.innerHTML = blocks.join('') || '<div class="panel"><h3>当前没有可执行的操作</h3><p class="panel-description">工单已关闭或当前状态与你的角色不匹配。状态和权限都由服务端判断。</p></div>';

    const value = id => (el(id) ? el(id).value.trim() : '');
    const run = async (button, action) => {
        setBusy(button, true);
        try {
            await action();
            toast('操作已提交');
            await dispatch();
        } catch (error) {
            if (error.status === 409 || error.status === 404) {
                toast(`${error.message} 已为你重新加载最新状态。`, true);
                await dispatch();
            } else {
                toast(error.message, true);
            }
        } finally {
            setBusy(button, false);
        }
    };

    panel.querySelectorAll('[data-act]').forEach(button => {
        button.addEventListener('click', () => {
            const action = button.dataset.act;
            if (action === 'comment') return run(button, async () => {
                if (!value('commentText')) throw new ApiError(400, '补充内容不能为空。');
                await postJson(`/api/v1/support/tickets/${ticket.id}/comments`, { expectedVersion: version, content: value('commentText') });
            });
            if (action === 'confirm') return run(button, () => postJson(`/api/v1/support/tickets/${ticket.id}/confirm`, {
                expectedVersion: version,
                rating: Number(value('rateScore') || 5),
                evaluation: value('rateText') || null
            }));
            if (action === 'reopen') return run(button, async () => {
                if (!value('commentText')) throw new ApiError(400, '退回时请在“补充说明”里写明未解决的原因。');
                await postJson(`/api/v1/support/tickets/${ticket.id}/reopen`, { expectedVersion: version, content: value('commentText') });
            });
            if (action === 'claim') return run(button, () => postJson(`/api/v1/support/tickets/${ticket.id}/claim`, { expectedVersion: version }));
            if (action === 'reply') return run(button, async () => {
                if (!value('replyText')) throw new ApiError(400, '回复内容不能为空。');
                await postJson(`/api/v1/support/tickets/${ticket.id}/replies`, { expectedVersion: version, content: value('replyText') });
            });
            if (action === 'solution') return run(button, async () => {
                if (!value('solutionText')) throw new ApiError(400, '方案内容不能为空。');
                await postJson(`/api/v1/support/tickets/${ticket.id}/solution`, { expectedVersion: version, content: value('solutionText') });
            });
            if (action === 'assign') return run(button, async () => {
                const agentId = value('agentSelect');
                if (!agentId) throw new ApiError(400, '请选择客服账号。');
                await postJson(`/api/v1/support/tickets/${ticket.id}/assign`, { expectedVersion: version, agentId });
            });
        });
    });

    if (me.role === 'ADMIN' && el('agentSelect')) loadAgentOptions(el('agentSelect'));
}

/** 客服下拉选项来自 /me 的公开账号列表；服务端在分配时仍会校验目标角色必须是客服。 */
async function loadAgentOptions(select) {
    try {
        const users = await getJson('/api/v1/support/accounts?role=CUSTOMER_SERVICE');
        select.innerHTML = users.length
            ? '<option value="">请选择客服</option>' + users.map(user => `<option value="${escapeHtml(user.id)}">${escapeHtml(user.username)}</option>`).join('')
            : '<option value="">数据库里还没有客服账号</option>';
    } catch (_) {
        select.innerHTML = '<option value="">无法读取客服账号</option>';
    }
}

/* ---------------- 售后助手 ---------------- */

/** 售后助手：最近一次的回答，用于生成草稿时的问题描述。 */
let lastAssistantAnswer = '';
/** 售后助手：本次对话的第一个问题，草稿描述的标题来源。 */
let lastAssistantQuestion = '';

function renderAssistant() {
    if (!assistantConversationId) assistantConversationId = crypto.randomUUID();
    view.innerHTML = `
        <div class="chat-shell">
            <div class="chat-log" id="chatLog"><div class="chat-inner" id="chatInner">
                <div class="bubble">
                    <div class="bubble-role"><span class="brand-mark" aria-hidden="true" style="width:22px;height:22px;font-size:12px;border-radius:7px">知</span>售后助手</div>
                    <div class="bubble-body">描述你的售后问题，我会先查你的模拟订单和产品资料，再给出有依据的建议。资料不足或需要人工处理时，我会整理一张草稿，由你确认后创建工单。</div>
                    <div class="chat-prompts">
                        <button class="prompt-chip" type="button" data-prompt="我的订单已经付款，但服务没有开通">我的订单已付款但未开通</button>
                        <button class="prompt-chip" type="button" data-prompt="帮我看看我有哪些订单和它们的状态">查看我的订单状态</button>
                        <button class="prompt-chip" type="button" data-prompt="我之前提的工单处理到哪一步了？">查询我的工单进度</button>
                    </div>
                </div>
            </div></div>
            <div class="chat-composer">
                <div class="composer-inner">
                    <div class="composer-row">
                        <textarea id="assistantInput" rows="2" maxlength="8000" placeholder="描述你遇到的售后问题…"></textarea>
                        <button class="button primary" type="button" id="assistantSend">发送</button>
                        <button class="button secondary" type="button" id="assistantStop" hidden>停止</button>
                    </div>
                    <p class="composer-status" id="assistantStatus"><span id="assistantStatusText">助手只做查询和建议；创建工单需要你确认。</span><button class="text-button" type="button" id="assistantDraft">生成工单草稿</button><button class="text-button" type="button" id="viewDraft" hidden>查看草稿</button><button class="text-button" type="button" id="manualFallback">手动建单</button></p>
                    <div class="draft-compose" id="draftCompose" hidden>
                        <div class="field">
                            <label class="field-label" for="draftCategory">分类（必选）</label>
                            <select id="draftCategory"></select>
                        </div>
                        <div class="field">
                            <label class="field-label" for="draftOrder">关联订单（可选）</label>
                            <select id="draftOrder"></select>
                        </div>
                        <div class="field">
                            <label class="field-label" for="draftDescription">问题描述</label>
                            <textarea id="draftDescription" maxlength="4000" rows="3"></textarea>
                        </div>
                        <p class="composer-status" id="draftComposeStatus"></p>
                        <div class="inline-fields">
                            <button class="button primary" type="button" id="draftComposeSubmit">生成草稿</button>
                            <button class="button secondary" type="button" id="draftComposeCancel">取消</button>
                        </div>
                    </div>
                </div>
            </div>
        </div>`;

    const input = el('assistantInput');
    el('assistantSend').addEventListener('click', () => sendAssistantMessage());
    el('assistantStop').addEventListener('click', () => assistantController && assistantController.abort());
    el('manualFallback').addEventListener('click', () => navigate('newTicket'));
    el('assistantDraft').addEventListener('click', () => openDraftCompose());
    el('viewDraft').addEventListener('click', () => { if (pendingDraft) offerDraft(pendingDraft); });
    el('draftComposeCancel').addEventListener('click', () => { el('draftCompose').hidden = true; });
    el('draftComposeSubmit').addEventListener('click', () => submitManualDraft());
    // 重新进入助手页时，只有确实存在待确认草稿才显示「查看草稿」。
    el('viewDraft').hidden = !pendingDraft;
    input.addEventListener('keydown', event => {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            sendAssistantMessage();
        }
    });
    view.querySelectorAll('[data-prompt]').forEach(button => {
        button.addEventListener('click', () => {
            input.value = button.dataset.prompt;
            sendAssistantMessage();
        });
    });
    input.focus();
}

function appendBubble(role, text) {
    const inner = el('chatInner');
    const bubble = document.createElement('div');
    bubble.className = `bubble bubble-${role}`;
    bubble.innerHTML = `<div class="bubble-role">${role === 'user' ? '我' : '售后助手'}</div><div class="bubble-body"></div>`;
    bubble.querySelector('.bubble-body').textContent = text || '';
    inner.appendChild(bubble);
    el('chatLog').scrollTop = el('chatLog').scrollHeight;
    return bubble.querySelector('.bubble-body');
}

let assistantController = null;

async function sendAssistantMessage() {
    if (assistantBusy) return;
    const input = el('assistantInput');
    const message = input.value.trim();
    if (!message) return;
    input.value = '';
    // 记录本次问题：草稿描述的标题与「问题：」部分取自这里，不依赖模型是否调用了草稿工具。
    if (!lastAssistantQuestion) lastAssistantQuestion = message;
    lastAssistantAnswer = '';
    appendBubble('user', message);
    const answer = appendBubble('assistant', '');
    const status = el('assistantStatusText');
    status.textContent = '正在查询订单和资料…';
    assistantBusy = true;
    el('assistantSend').disabled = true;
    el('assistantStop').hidden = false;
    assistantController = new AbortController();

    try {
        await streamAssistant(message, answer, status);
    } catch (error) {
        if (error.name === 'AbortError') {
            status.textContent = '已停止这次回答。';
        } else {
            answer.textContent = (answer.textContent || '') + `\n\n本次回答失败：${error.message}`;
            status.textContent = '助手暂时不可用，你仍然可以手动创建工单。';
            appendManualFallback(answer);
        }
    } finally {
        assistantBusy = false;
        el('assistantSend').disabled = false;
        el('assistantStop').hidden = true;
        assistantController = null;
    }
}

/** 解析 SSE：事件以空行分隔，data 行内是 AgentEvent JSON。 */
async function streamAssistant(message, answerNode, statusNode) {
    const headers = new Headers({ 'Content-Type': 'application/json; charset=utf-8', Accept: 'text/event-stream' });
    if (token) headers.set('Authorization', `Bearer ${token}`);
    const response = await window.fetch('/api/v1/support/assistant/stream', {
        method: 'POST',
        headers,
        body: JSON.stringify({ message, conversationId: assistantConversationId, knowledgeBaseId: 'product-support' }),
        signal: assistantController.signal
    });
    if (!response.ok || !response.body) {
        throw new ApiError(response.status, await readError(response, `助手请求失败（${response.status}）`));
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const blocks = buffer.split(/\r?\n\r?\n/);
        buffer = blocks.pop();
        for (const block of blocks) handleSseBlock(block, answerNode, statusNode);
    }
    if (buffer.trim()) handleSseBlock(buffer, answerNode, statusNode);
}

function handleSseBlock(block, answerNode, statusNode) {
    const eventMatch = block.match(/^event:(.+)$/m);
    const dataLines = [...block.matchAll(/^data:(.*)$/gm)].map(match => match[1]);
    if (!eventMatch || !dataLines.length) return;
    const type = eventMatch[1].trim();
    let payload;
    try {
        payload = JSON.parse(dataLines.join('\n'));
    } catch (_) {
        return;
    }
    const data = payload.data || {};
    if (type === 'answer.delta' && data.content) {
        answerNode.textContent += data.content;
        // 记录本次回答，供「生成工单草稿」组装描述使用（页面不做模型调用）。
        lastAssistantAnswer = answerNode.textContent.slice(0, 2000);
        el('chatLog').scrollTop = el('chatLog').scrollHeight;
    } else if (type === 'tool.started') {
        statusNode.textContent = `正在调用 ${toolLabel(data.toolName)}…`;
    } else if (type === 'tool.completed') {
        statusNode.textContent = `${toolLabel(data.toolName)} 已返回结果`;
    } else if (type === 'support.ticket-draft.ready' && data.draft) {
        offerDraft(data.draft);
    } else if (type === 'support.knowledge.evidence') {
        renderEvidence(answerNode, data);
    } else if (type === 'task.completed') {
        statusNode.textContent = '回答完成。如果本次生成了待确认草稿，请在下方核对后创建工单；也可以随时手动建单。';
    } else if (type === 'task.failed') {
        statusNode.textContent = data.message || '本次回答失败，可以稍后重试或手动创建工单。';
        appendManualFallback(answerNode);
    }
}

const TOOL_LABELS = Object.freeze({
    knowledge_search: '检索售后资料',
    support_orders: '查询我的订单',
    support_order: '查询订单详情',
    support_tickets: '查询我的工单',
    support_ticket: '查询工单详情',
    support_categories: '查询售后分类',
    support_ticket_draft: '整理工单草稿',
    current_date: '确认当前日期'
});

function toolLabel(name) {
    return TOOL_LABELS[name] || name;
}

/** 显示后端从授权检索结果投影出的出处；不依赖模型自行复述文件名或页码。 */
function renderEvidence(answerNode, evidence) {
    const bubble = answerNode.closest('.bubble');
    if (!bubble) return;
    bubble.querySelector('[data-evidence]')?.remove();
    const section = document.createElement('section');
    section.className = `evidence-box ${evidence.evidenceSufficient ? '' : 'evidence-empty'}`;
    section.dataset.evidence = 'true';
    // 过滤 null/非对象元素：投影只保证来源来自授权检索，不保证每个元素都可用。
    const sources = (Array.isArray(evidence.sources) ? evidence.sources : [])
        .filter(source => source && typeof source === 'object');
    section.innerHTML = evidence.evidenceSufficient && sources.length
        ? `<strong>可核对出处</strong><ul>${sources.map(source => {
            const location = source.pageNumber ? `第 ${source.pageNumber} 页` : `片段 ${Number(source.chunkIndex ?? 0) + 1}`;
            return `<li><span>${escapeHtml(source.filename)} · ${escapeHtml(location)}</span><small>${escapeHtml(source.excerpt || '')}</small></li>`;
        }).join('')}</ul>`
        : `<strong>知识库暂无依据</strong><p>${escapeHtml(evidence.nextAction || evidence.message || '你可以补充资料或手动创建工单。')}</p>`;
    bubble.appendChild(section);
}

function appendManualFallback(answerNode) {
    const bubble = answerNode.closest('.bubble');
    if (!bubble || bubble.querySelector('[data-manual-fallback]')) return;
    const button = document.createElement('button');
    button.className = 'button secondary small';
    button.type = 'button';
    button.dataset.manualFallback = 'true';
    button.textContent = '改为手动建单';
    button.addEventListener('click', () => navigate('newTicket'));
    bubble.appendChild(button);
}

/* ---------------- 售后助手：主动生成草稿 ---------------- */

/**
 * 展开「生成工单草稿」表单。
 *
 * <p>为什么需要这个入口：草稿原先只能由模型调用 support_ticket_draft 产生，而真实模型实测很少
 * 主动调用它，用户因此很难走到建单流程。分类和关联订单必须从接口拉取下拉项而不是让用户输入 ID，
 * 这样既不依赖模型，也不会因为手输错误而关联失败。
 */
async function openDraftCompose() {
    const compose = el('draftCompose');
    const status = el('draftComposeStatus');
    compose.hidden = false;
    if (el('viewDraft')) el('viewDraft').hidden = true;
    status.textContent = '正在读取分类和我的订单…';
    status.classList.remove('error');
    try {
        const [categories, orders] = await Promise.all([
            getJson('/api/v1/support/categories'),
            getJson('/api/v1/support/orders').catch(() => [])
        ]);
        const enabled = categories.filter(category => category.enabled);
        el('draftCategory').innerHTML = enabled.length
            ? enabled.map(category => `<option value="${escapeHtml(category.id)}">${escapeHtml(category.name)}</option>`).join('')
            : '<option value="">没有可用分类，请联系管理员配置</option>';
        el('draftOrder').innerHTML = '<option value="">不关联订单</option>'
            + orders.map(order => `<option value="${escapeHtml(order.id)}">${escapeHtml(order.orderNo)} · ${escapeHtml(order.productName || '')}</option>`).join('');
        // 描述默认带入本次对话，用户仍可修改后提交。
        el('draftDescription').value = draftDescriptionFromConversation();
        status.textContent = enabled.length
            ? '分类与订单来自服务端；确认后才会真正创建工单。'
            : '当前没有启用中的分类，无法生成草稿。';
        if (enabled.length) el('draftDescription').focus();
    } catch (error) {
        status.textContent = error.message;
        status.classList.add('error');
    }
}

/**
 * 用本次对话组装草稿描述：先写用户的问题，再附上助手的回答，便于客服了解上下文。
 * 没有对话时（用户可能先点「生成工单草稿」）回退到输入框里已经写好的内容，避免出现空描述。
 */
function draftDescriptionFromConversation() {
    const question = lastAssistantQuestion || el('assistantInput').value.trim();
    const answer = lastAssistantAnswer;
    const parts = [];
    if (question) parts.push(`问题：${question}`);
    if (answer) parts.push(`助手回答：${answer}`);
    return parts.join('\n\n').slice(0, 4000);
}

async function submitManualDraft() {
    const button = el('draftComposeSubmit');
    const status = el('draftComposeStatus');
    const description = el('draftDescription').value.trim();
    const categoryId = el('draftCategory').value;
    const orderId = el('draftOrder').value || null;
    if (!description) {
        status.textContent = '问题描述不能为空。';
        status.classList.add('error');
        return;
    }
    if (!categoryId) {
        status.textContent = '请选择分类。';
        status.classList.add('error');
        return;
    }
    setBusy(button, true, '正在生成…');
    status.classList.remove('error');
    try {
        // 与模型工具返回同一种草稿对象；后端固定 confirmed=false 且不写数据库。
        const draft = await postJson('/api/v1/support/ticket-drafts', { description, categoryId, orderId });
        offerDraft(draft);
    } catch (error) {
        status.textContent = error.message;
        status.classList.add('error');
    } finally {
        setBusy(button, false);
    }
}

function offerDraft(draft) {
    pendingDraft = draft;
    // 打开确认弹窗前收起造草稿表单：两者不能同时占住页面，弹窗也会挡住表单。
    el('draftCompose').hidden = true;
    if (el('viewDraft')) el('viewDraft').hidden = true;
    el('draftBody').innerHTML = `
        <dl class="draft-grid">
            <div><dt>标题</dt><dd>${escapeHtml(draft.title)}</dd></div>
            <div><dt>分类</dt><dd>${escapeHtml(draft.categoryName || draft.categoryId)}</dd></div>
            <div><dt>问题描述</dt><dd>${escapeHtml(draft.description)}</dd></div>
            <div><dt>关联订单</dt><dd class="mono">${draft.orderId ? escapeHtml(draft.orderId) : '未关联'}</dd></div>
        </dl>
        <p class="draft-note">草稿尚未创建工单。确认后才会提交给客服，届时服务端会再次校验分类、订单归属和重复提交。</p>`;
    el('draftStatus').textContent = '';
    el('draftModal').hidden = false;
    el('draftModal').setAttribute('aria-hidden', 'false');
    el('draftConfirm').disabled = false;
    el('draftCancel').textContent = '暂不创建';
}

function closeDraftModal() {
    el('draftModal').hidden = true;
    el('draftModal').setAttribute('aria-hidden', 'true');
}

/**
 * 取消确认弹窗：收起造草稿表单并保留这张草稿，用户可以用「查看草稿」重新打开，
 * 不必重新填写；只有确认建单或切换路由登录状态时才丢弃。
 */
function dismissDraftModal() {
    el('draftCompose').hidden = true;
    closeDraftModal();
    if (el('viewDraft')) el('viewDraft').hidden = !pendingDraft;
}

async function confirmDraft() {
    // 先快照：下面的 await 期间可能收到第二张草稿或用户点了「暂不创建」，
    // 每次都从快照取字段，避免「确认的是 A、提交的是 B」或对 null 取属性。
    const draft = pendingDraft;
    if (!draft) return;
    const button = el('draftConfirm');
    const status = el('draftStatus');
    setBusy(button, true, '正在创建…');
    try {
        // 先检查是否已经建单：避免用户重复点击或刷新后重复提交。
        const existingResponse = await api(`/api/v1/support/ticket-drafts/${encodeURIComponent(draft.requestId)}`);
        if (existingResponse.status === 200) {
            const existing = await existingResponse.json();
            status.textContent = `这张草稿已经创建过工单，直接打开原工单。`;
            closeDraftModal();
            toast('该草稿已创建过工单，已为你打开。');
            navigate('ticket', existing.id);
            if (pendingDraft === draft) pendingDraft = null;
            return;
        }
        // 204 才表示「尚未建单」，可以继续提交。其他状态（403 / 500 / 网络代理错误）不能当作
        // 「不存在」处理，否则会在查询失败时贸然建单，把重复提交的风险重新引入。
        if (existingResponse.status !== 204) {
            throw new ApiError(
                existingResponse.status,
                await readError(existingResponse, `无法确认草稿状态（${existingResponse.status}），请稍后重试。`)
            );
        }
        const ticket = await postJson('/api/v1/support/tickets', {
            requestId: draft.requestId,
            title: draft.title,
            description: draft.description,
            categoryId: draft.categoryId,
            orderId: draft.orderId || null,
            confirmed: true
        });
        closeDraftModal();
        toast('工单已创建，客服会尽快处理。');
        navigate('ticket', ticket.id);
        if (pendingDraft === draft) pendingDraft = null;
    } catch (error) {
        status.textContent = error.message;
    } finally {
        setBusy(button, false);
    }
}

/* ---------------- 管理端：分类 ---------------- */

async function renderCategories() {
    categories = await getJson('/api/v1/support/categories');
    view.innerHTML = `
        <div class="view-inner">
            <div class="section-head"><div><h2>分类管理</h2><p>分类采用停用而不是删除；已停用分类不能被新工单选用。</p></div></div>
            <div class="panel">
                <h3>新建分类</h3>
                <form id="categoryForm" class="inline-fields">
                    <div class="field"><label class="field-label" for="categoryName">分类名称</label>
                        <input id="categoryName" maxlength="80" required placeholder="例如：虚构订阅开通问题"></div>
                    <button class="button primary" type="submit">创建</button>
                </form>
            </div>
            <div class="panel">
                <h3>现有分类</h3>
                <div class="table-wrap"><table class="data-table">
                    <thead><tr><th>名称</th><th>状态</th><th>版本</th><th></th></tr></thead>
                    <tbody>${categories.map(category => `
                        <tr>
                            <td>${escapeHtml(category.name)}</td>
                            <td>${category.enabled ? '<span class="chip">启用</span>' : '<span class="chip">已停用</span>'}</td>
                            <td class="mono">${category.version}</td>
                            <td><div class="actions">
                                <button class="button small" type="button" data-toggle="${escapeHtml(category.id)}" data-enabled="${category.enabled}">
                                    ${category.enabled ? '停用' : '启用'}
                                </button>
                            </div></td>
                        </tr>`).join('')}</tbody>
                </table></div>
            </div>
        </div>`;

    el('categoryForm').addEventListener('submit', async event => {
        event.preventDefault();
        const button = event.submitter;
        setBusy(button, true);
        try {
            await postJson('/api/v1/support/categories', { name: el('categoryName').value.trim(), enabled: true });
            toast('分类已创建');
            await dispatch();
        } catch (error) {
            toast(error.message, true);
        } finally {
            setBusy(button, false);
        }
    });

    view.querySelectorAll('[data-toggle]').forEach(button => {
        button.addEventListener('click', async () => {
            const category = categories.find(item => item.id === button.dataset.toggle);
            setBusy(button, true);
            try {
                await putJson(`/api/v1/support/categories/${category.id}`, { name: category.name, enabled: !category.enabled });
                toast(category.enabled ? '分类已停用' : '分类已启用');
                await dispatch();
            } catch (error) {
                toast(error.message, true);
                setBusy(button, false);
            }
        });
    });
}

/* ---------------- 管理端：分配与全部工单 ---------------- */

async function renderAssignment() {
    const tickets = await getJson('/api/v1/support/tickets?view=all');
    if (!isCurrentRender()) return;
    view.innerHTML = `
        <div class="view-inner">
            <div class="section-head"><div><h2>工单分配</h2><p>共 ${tickets.length} 张工单。打开工单后可以把待受理或处理中的工单分配给客服。</p></div></div>
            ${tickets.length ? `<div class="ticket-list">${tickets.map(ticket => `
                <button class="ticket-row" type="button" data-ticket="${escapeHtml(ticket.id)}">
                    <span class="ticket-row-title">${escapeHtml(ticket.title)}</span>
                    <span class="ticket-row-side">${statusTag(ticket.status)}</span>
                    <span class="ticket-row-meta">
                        <span class="mono">${escapeHtml(ticket.id.slice(0, 8))}</span>
                        <span>${ticket.assignedTo ? '已分配' : '未分配'}</span>
                        <span>更新于 ${formatTime(ticket.updatedAt)}</span>
                    </span>
                </button>`).join('')}</div>`
            : '<div class="empty"><strong>还没有任何工单</strong>用户提交售后问题后会显示在这里。</div>'}
        </div>`;
    view.querySelectorAll('[data-ticket]').forEach(button => {
        button.addEventListener('click', () => navigate('ticket', button.dataset.ticket));
    });
}

/* ---------------- 管理端：产品与模拟订单 ---------------- */

async function renderCatalog() {
    const [products, orders] = await Promise.all([
        getJson('/api/v1/support/products'),
        getJson('/api/v1/support/admin/orders').catch(() => [])
    ]);
    const users = await getJson('/api/v1/support/accounts?role=USER').catch(() => []);

    if (!isCurrentRender()) return;
    view.innerHTML = `
        <div class="view-inner">
            <div class="section-head"><div><h2>产品与模拟订单</h2>
                <p>全部数据均为虚构，标记 simulated=true；系统不连接真实支付，也没有修改付款或开通状态的接口。</p></div></div>

            <div class="panel">
                <h3>录入虚构产品</h3>
                <form id="productForm" class="form-grid">
                    <div class="field"><label class="field-label" for="productSku">SKU</label><input id="productSku" maxlength="32" required placeholder="DEMO-SUB-PRO"></div>
                    <div class="field"><label class="field-label" for="productName">产品名称</label><input id="productName" maxlength="120" required placeholder="虚构专业版订阅"></div>
                    <div class="field"><label class="field-label" for="productDesc">说明</label><input id="productDesc" maxlength="1000" required placeholder="仅用于项目演示"></div>
                    <div class="field" style="display:flex;align-items:flex-end"><button class="button primary block" type="submit">创建产品</button></div>
                </form>
            </div>

            <div class="panel">
                <h3>录入演示订单</h3>
                <p class="panel-description">合法组合：待付款+未开通、已付款+未开通、已付款+已开通。待付款+已开通会被拒绝。</p>
                <form id="orderForm" class="form-grid">
                    <div class="field"><label class="field-label" for="orderUser">用户</label>
                        <select id="orderUser">${users.map(user => `<option value="${escapeHtml(user.id)}">${escapeHtml(user.username)}</option>`).join('') || '<option value="">没有普通用户账号</option>'}</select></div>
                    <div class="field"><label class="field-label" for="orderProduct">产品</label>
                        <select id="orderProduct">${products.map(product => `<option value="${escapeHtml(product.id)}">${escapeHtml(product.name)}</option>`).join('') || '<option value="">请先创建产品</option>'}</select></div>
                    <div class="field"><label class="field-label" for="orderPayment">付款状态</label>
                        <select id="orderPayment"><option value="PAID">PAID 已付款</option><option value="PENDING_PAYMENT">PENDING_PAYMENT 待付款</option></select></div>
                    <div class="field"><label class="field-label" for="orderService">开通状态</label>
                        <select id="orderService"><option value="NOT_ACTIVATED">NOT_ACTIVATED 未开通</option><option value="ACTIVATED">ACTIVATED 已开通</option></select></div>
                    <div class="field" style="display:flex;align-items:flex-end"><button class="button primary block" type="submit">创建订单</button></div>
                </form>
            </div>

            <div class="panel">
                <h3>产品</h3>
                ${products.length ? `<div class="table-wrap"><table class="data-table">
                    <thead><tr><th>SKU</th><th>名称</th><th>模拟数据</th></tr></thead>
                    <tbody>${products.map(product => `<tr>
                        <td class="mono">${escapeHtml(product.sku)}</td>
                        <td>${escapeHtml(product.name)}</td>
                        <td>${product.simulated ? '<span class="chip">simulated</span>' : '—'}</td>
                    </tr>`).join('')}</tbody></table></div>` : '<p class="muted">还没有产品。</p>'}
            </div>

            <div class="panel">
                <h3>演示订单</h3>
                <p class="panel-description">管理员造单使用 requestId 幂等；重复提交相同内容不会新增订单。</p>
                ${orders.length ? `<div class="table-wrap"><table class="data-table">
                    <thead><tr><th>订单号</th><th>产品</th><th>付款</th><th>开通</th></tr></thead>
                    <tbody>${orders.map(order => `<tr>
                        <td class="mono">${escapeHtml(order.orderNo)}</td>
                        <td>${escapeHtml(order.productName)}</td>
                        <td>${escapeHtml(order.paymentStatus)}</td>
                        <td>${escapeHtml(order.serviceStatus)}</td>
                    </tr>`).join('')}</tbody></table></div>` : '<p class="muted">还没有订单，或当前账号不能读取用户订单列表。</p>'}
            </div>
        </div>`;

    el('productForm').addEventListener('submit', async event => {
        event.preventDefault();
        const button = event.submitter;
        setBusy(button, true);
        try {
            await postJson('/api/v1/support/products', {
                sku: el('productSku').value.trim(),
                name: el('productName').value.trim(),
                description: el('productDesc').value.trim()
            });
            toast('产品已创建');
            await dispatch();
        } catch (error) {
            toast(error.message, true);
            setBusy(button, false);
        }
    });

    el('orderForm').addEventListener('submit', async event => {
        event.preventDefault();
        const button = event.submitter;
        setBusy(button, true);
        try {
            await postJson('/api/v1/support/admin/orders', {
                requestId: crypto.randomUUID(),
                userId: el('orderUser').value,
                productId: el('orderProduct').value,
                paymentStatus: el('orderPayment').value,
                serviceStatus: el('orderService').value
            });
            toast('演示订单已创建');
            await dispatch();
        } catch (error) {
            toast(error.message, true);
            setBusy(button, false);
        }
    });
}

/* ---------------- 知识库（用户与管理端共用） ---------------- */

async function renderKnowledge() {
    const bases = await getJson('/api/v1/knowledge-bases');
    const writable = bases.filter(base => base.writable);
    const preferredId = selectedKnowledgeBaseId || (me.role === 'ADMIN' ? 'product-support' : null);
    const selected = bases.find(base => base.id === preferredId) || writable[0] || bases[0];
    if (selected) selectedKnowledgeBaseId = selected.id;
    let documents = selected ? await getJson(`/api/v1/knowledge-bases/${encodeURIComponent(selected.id)}/documents`) : [];

    if (!isCurrentRender()) return;
    view.innerHTML = `
        <div class="view-inner">
            <div class="section-head"><div><h2>知识库</h2>
                <p>公共库 product-support 由管理员维护；私人文档按账号隔离，其他账号和管理员都不能读取。</p></div></div>
            <div class="panel">
                <h3>知识库列表</h3>
                ${bases.length ? `<div class="field"><label class="field-label" for="knowledgeViewer">查看知识库</label><select id="knowledgeViewer">${bases.map(base => `<option value="${escapeHtml(base.id)}" ${base.id === selected.id ? 'selected' : ''}>${escapeHtml(base.id)} · ${base.visibility === 'PUBLIC' ? '公共' : '私人'}</option>`).join('')}</select></div>` : ''}
                <div class="table-wrap"><table class="data-table">
                    <thead><tr><th>名称</th><th>范围</th><th>可写</th></tr></thead>
                    <tbody>${bases.map(base => `<tr>
                        <td>${escapeHtml(base.id)}</td>
                        <td>${base.visibility === 'PUBLIC' ? '<span class="chip">公共售后资料</span>' : '<span class="chip">私人</span>'}</td>
                        <td>${base.writable ? '是' : '否'}</td>
                    </tr>`).join('')}</tbody></table></div>
            </div>
            ${selected && selected.writable ? `
            <div class="panel">
                <h3>上传文档到 ${escapeHtml(selected.id)}</h3>
                <p class="panel-description">支持 PDF / TXT / Markdown，单文件不超过 20MB。上传后异步解析、分块并向量化；检索结果会带文件名与页码。</p>
                <form id="uploadForm" class="inline-fields">
                    <div class="field"><label class="field-label" for="knowledgeBase">目标知识库</label>
                        <select id="knowledgeBase">${writable.map(base => `<option value="${escapeHtml(base.id)}" ${base.id === selected.id ? 'selected' : ''}>${escapeHtml(base.id)}</option>`).join('')}</select></div>
                    <div class="field"><label class="field-label" for="documentInput">选择文件</label><input id="documentInput" type="file" accept=".pdf,.txt,.md,.markdown" required></div>
                    <button class="button primary" type="submit">上传</button>
                </form>
            </div>` : selected ? `<div class="panel"><h3>${escapeHtml(selected.id)}</h3><p class="panel-description">当前知识库只读；公共产品资料只有管理员可以维护。</p></div>` : ''}
            ${selected ? `
            <div class="panel">
                <h3>文档（${escapeHtml(selected.id)}）</h3>
                <div id="documentArea"></div>
            </div>` : '<div class="empty"><strong>没有可用知识库</strong>当前账号还没有可读取的知识库。</div>'}
        </div>`;

    if (selected) renderDocuments(documents, selected.id, Boolean(selected.writable));
    // 有文档还在处理中时启动轮询；没有则确保上一次的定时器已停止（例如换库、重进页面）。
    if (selected && documents.some(doc => doc.status === 'PROCESSING' || doc.status === 'DELETING')) {
        pollDocumentStatus(selected.id, Boolean(selected.writable));
    } else {
        stopDocumentPolling();
    }

    if (el('knowledgeViewer')) {
        el('knowledgeViewer').addEventListener('change', async event => {
            selectedKnowledgeBaseId = event.target.value;
            await dispatch();
        });
    }

    if (el('uploadForm')) {
        el('uploadForm').addEventListener('submit', async event => {
            event.preventDefault();
            const button = event.submitter;
            const file = el('documentInput').files[0];
            if (!file) return;
            setBusy(button, true, '上传中…');
            try {
                const form = new FormData();
                form.append('file', file);
                const base = el('knowledgeBase').value;
                const response = await api(`/api/v1/knowledge-bases/${encodeURIComponent(base)}/documents`, { method: 'POST', body: form });
                if (!response.ok) throw new ApiError(response.status, await readError(response, '上传失败'));
                toast('文件已上传，正在后台处理。');
                await dispatch();
            } catch (error) {
                toast(error.message, true);
                setBusy(button, false);
            }
        });
    }
}

const DOCUMENT_LABELS = Object.freeze({ PROCESSING: '处理中', READY: '已就绪', FAILED: '处理失败', DELETING: '删除中' });

/** 文档后台处理是异步的：轮询只刷新文档区，避免整页重载打断用户操作。 */
let documentPollTimer = null;
let documentPollDeadline = 0;

function stopDocumentPolling() {
    if (documentPollTimer !== null) {
        clearTimeout(documentPollTimer);
        documentPollTimer = null;
    }
}

/**
 * 只要还有文档处于处理中/删除中，就按固定间隔重新拉取文档列表。
 *
 * <p>约束：只更新文档区域而不整页重载；有总时长上限，超时后停止并提示手动刷新；
 * 切路由与登出都会停止（见 dispatch 与 signOut）。
 */
function pollDocumentStatus(knowledgeBaseId, writable) {
    stopDocumentPolling();
    documentPollDeadline = Date.now() + 120000;
    const tick = async () => {
        documentPollTimer = null;
        if (Date.now() > documentPollDeadline) {
            renderDocumentPollHint('后台处理时间较长，已停止自动刷新；可以手动刷新页面查看最新状态。');
            return;
        }
        try {
            const documents = await getJson(`/api/v1/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`);
            const pending = documents.some(doc => doc.status === 'PROCESSING' || doc.status === 'DELETING');
            renderDocuments(documents, knowledgeBaseId, writable, {
                fromPoll: true,
                hint: pending ? '正在后台处理，状态会自动刷新。' : null
            });
            if (pending) documentPollTimer = setTimeout(tick, 2000);
            else renderDocumentPollHint(null);
        } catch (_) {
            // 轮询失败不打扰用户：下一次仍会重试，超过总时长后自动停止。
            documentPollTimer = setTimeout(tick, 3000);
        }
    };
    documentPollTimer = setTimeout(tick, 2000);
}

/** 在文档区上方显示/清除自动刷新提示，让用户知道状态会自己更新，而不是页面卡住。 */
function renderDocumentPollHint(text) {
    const hint = el('documentPollHint');
    if (!hint) return;
    hint.textContent = text || '';
    hint.hidden = !text;
}

/**
 * 渲染文档表格。
 *
 * @param options.fromPoll 轮询触发的更新：跳过渲染竞态守卫（这是当前视图的主动刷新）
 * @param options.hint     写在文档区顶部的提示文本（例如"正在后台处理，状态会自动刷新"）
 */
function renderDocuments(documents, knowledgeBaseId, writable, options = {}) {
    if (!options.fromPoll && !isCurrentRender()) return;
    const area = el('documentArea');
    if (!documents.length) {
        area.innerHTML = `<p class="muted" id="documentPollHint" hidden></p><p class="muted">这个知识库里还没有文档。上传产品手册、开通说明或常见故障后，助手检索时会引用文件名和页码。</p>`;
        if (options.hint) renderDocumentPollHint(options.hint);
        return;
    }
    area.innerHTML = `<p class="muted" id="documentPollHint" hidden></p><div class="table-wrap"><table class="data-table">
        <thead><tr><th>文件名</th><th>状态</th><th>片段</th><th>大小</th><th></th></tr></thead>
        <tbody>${documents.map(doc => `<tr>
            <td>${escapeHtml(doc.filename)}</td>
            <td>${escapeHtml(DOCUMENT_LABELS[doc.status] || doc.status)}</td>
            <td class="mono">${doc.chunkCount ?? '—'}</td>
            <td class="mono">${Math.round((doc.size || 0) / 1024)} KB</td>
            <td><div class="actions">
                ${writable && doc.status === 'FAILED' ? `<button class="button small" type="button" data-retry="${escapeHtml(doc.id)}">重试</button>` : ''}
                ${writable ? `<button class="button small danger" type="button" data-delete="${escapeHtml(doc.id)}">删除</button>` : ''}
            </div></td>
        </tr>`).join('')}</tbody></table></div>`;

    if (options.hint) renderDocumentPollHint(options.hint);

    area.querySelectorAll('[data-retry]').forEach(button => button.addEventListener('click', async () => {
        setBusy(button, true);
        try {
            const base = knowledgeBaseId;
            await postJson(`/api/v1/knowledge-bases/${encodeURIComponent(base)}/documents/${encodeURIComponent(button.dataset.retry)}/retry`, {});
            toast('已请求重新处理');
            await dispatch();
        } catch (error) {
            toast(error.message, true);
            setBusy(button, false);
        }
    }));
    area.querySelectorAll('[data-delete]').forEach(button => button.addEventListener('click', async () => {
        setBusy(button, true);
        try {
            const base = knowledgeBaseId;
            const response = await api(`/api/v1/knowledge-bases/${encodeURIComponent(base)}/documents/${encodeURIComponent(button.dataset.delete)}`, { method: 'DELETE' });
            if (!response.ok) throw new ApiError(response.status, await readError(response, '删除失败'));
            toast('文档已删除');
            await dispatch();
        } catch (error) {
            toast(error.message, true);
            setBusy(button, false);
        }
    }));
}

/* ---------------- 启动 ---------------- */

async function loadHealth() {
    const badge = el('modeBadge');
    const dot = el('modeDot');
    try {
        health = await window.fetch('/api/health').then(response => response.json());
        const model = health.aiEnabled ? '真实模型已启用' : '演示模式（未调用模型）';
        badge.textContent = model;
        dot.className = 'status-dot ' + (health.aiEnabled ? 'on' : 'off');
    } catch (_) {
        badge.textContent = '服务状态未知';
        dot.className = 'status-dot';
    }
}

async function loadCategoriesIfPossible() {
    try {
        categories = await getJson('/api/v1/support/categories');
    } catch (_) {
        categories = [];
    }
}

async function start() {
    await loadHealth();
    const config = await window.fetch('/api/v1/auth/config').then(response => response.json()).catch(() => null);
    if (!config || !config.enabled) {
        showAuth('当前服务没有开启登录功能，售后工作台不可用。请用启用 JWT 与数据库的模式启动。', true);
        return;
    }
    el('logout').addEventListener('click', () => signOut());
    if (!token) {
        showAuth('请使用已有账号登录。');
        return;
    }
    try {
        await loadIdentity();
        hideAuth();
        renderAccount();
        await loadCategoriesIfPossible();
        await dispatch();
    } catch (error) {
        if (error.status === 403) {
            showAuth(error.message + '（游客账号不能进入售后模块。）', true);
        } else {
            showAuth(error.message, true);
        }
    }
}

el('authForm').addEventListener('submit', async event => {
    event.preventDefault();
    const register = event.submitter && event.submitter.value === 'register';
    const button = event.submitter;
    const status = el('authStatus');
    setBusy(button, true);
    try {
        await login(el('username').value.trim(), el('password').value, register);
        await loadIdentity();
        hideAuth();
        renderAccount();
        await loadCategoriesIfPossible();
        toast(register ? '账号已创建，已进入售后工作台。' : '已登录。');
        await dispatch();
    } catch (error) {
        status.textContent = error.message;
        status.classList.add('error');
    } finally {
        setBusy(button, false);
    }
});

el('draftConfirm').addEventListener('click', confirmDraft);
el('draftCancel').addEventListener('click', () => dismissDraftModal());
el('openSidebar').addEventListener('click', () => shell.classList.add('nav-open'));
el('closeSidebar').addEventListener('click', () => shell.classList.remove('nav-open'));
el('sidebarOverlay').addEventListener('click', () => shell.classList.remove('nav-open'));
// hash 变化时刷新视图。渲染序号会丢弃过期结果，所以这里无需额外去重。
window.addEventListener('hashchange', () => { dispatch(); });
start();
