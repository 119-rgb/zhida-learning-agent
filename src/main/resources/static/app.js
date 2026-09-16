let authToken = sessionStorage.getItem('zhida-token');
const authModal = document.querySelector('#authModal');
const authStatus = document.querySelector('#authStatus');
const appShell = document.querySelector('.app-shell');
const accountSummary = document.querySelector('#accountSummary');
const accountName = document.querySelector('#accountName');
const accountHint = document.querySelector('#accountHint');
const accountAvatar = document.querySelector('#accountAvatar');
const openAuthButton = document.querySelector('#openAuth');
const logoutButton = document.querySelector('#logout');

function showAuth(message) {
    authModal.hidden = false;
    authModal.setAttribute('aria-hidden', 'false');
    appShell.setAttribute('inert', '');
    appShell.setAttribute('aria-hidden', 'true');
    if (message) authStatus.textContent = message;
    window.setTimeout(() => document.querySelector('#username').focus(), 30);
}

function hideAuth() {
    authModal.hidden = true;
    authModal.setAttribute('aria-hidden', 'true');
    appShell.removeAttribute('inert');
    appShell.removeAttribute('aria-hidden');
}

function renderLoggedOutAccount() {
    accountName.textContent = '未登录';
    accountHint.textContent = '登录后保存个人数据';
    accountAvatar.textContent = '访';
    openAuthButton.hidden = false;
    logoutButton.hidden = true;
}

function setAuthBusy(busy, activeButton) {
    for (const button of document.querySelectorAll('#authPanel button')) button.disabled = busy;
    if (activeButton) {
        if (!activeButton.dataset.label) activeButton.dataset.label = activeButton.textContent;
        activeButton.textContent = busy ? '请稍候…' : activeButton.dataset.label;
    }
}

const fetch = async (url, options = {}) => {
    const headers = new Headers(options.headers || {});
    if (authToken) headers.set('Authorization', `Bearer ${authToken}`);
    const response = await window.fetch(url, { ...options, headers });
    if (response.status === 401) {
        authToken = null;
        sessionStorage.removeItem('zhida-token');
        renderLoggedOutAccount();
        showAuth('登录状态已过期，请重新登录。');
    }
    return response;
};
const form = document.querySelector('#questionForm');
const questionInput = document.querySelector('#question');
const submitButton = document.querySelector('#submitButton');
const statusText = document.querySelector('#statusText');
const messages = document.querySelector('#messages');
const welcome = document.querySelector('#welcome');
const modeBadge = document.querySelector('#modeBadge');
const modeDot = document.querySelector('#modeDot');
const documentFile = document.querySelector('#documentFile');
const documentCount = document.querySelector('#documentCount');
const knowledgeStatus = document.querySelector('#knowledgeStatus');
const sidebar = document.querySelector('#sidebar');
const sidebarOverlay = document.querySelector('#sidebarOverlay');

let conversationId = crypto.randomUUID();
let knowledgeBaseId = 'default';
const REQUEST_TIMEOUT_MS = 120_000;
let answerElement;
let answerBuffer = '';
let thinkingDetails;
let thinkingSummary;
let thinkingList;
let planTotal = 0;
let activeRequest;
let activeTaskId;
let stoppedByUser = false;
let documentRefreshTimer;
const DOCUMENT_STATUS_LABELS = Object.freeze({
    PROCESSING: '处理中',
    READY: '已就绪',
    FAILED: '处理失败',
    DELETING: '删除中'
});
const stopButton = document.querySelector('#stopButton');
stopButton.addEventListener('click', async () => {
    stoppedByUser = true;
    const controller = activeRequest;
    const taskId = activeTaskId;
    controller?.abort();
    if (taskId) {
        try { await fetch(`/api/v1/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST' }); }
        catch (_) { /* Disconnect also cancels the server-side subscription. */ }
    }
});

initializeAccount();

async function initializeAccount() {
    const config = await window.fetch('/api/v1/auth/config')
        .then(response => {
            if (!response.ok) throw new Error('认证服务暂不可用');
            return response.json();
        })
        .catch(() => null);
    if (!config) {
        accountSummary.hidden = true;
        showAuth('暂时无法连接认证服务，请稍后刷新页面。');
        for (const control of document.querySelectorAll('#authPanel input, #authPanel button')) control.disabled = true;
        checkHealth();
        return;
    }
    accountSummary.hidden = !config.enabled;
    if (!config.enabled) hideAuth();
    if (config.enabled && authToken) {
        const expiry = tokenExpiry(authToken);
        if (!expiry || expiry <= Date.now()) {
            authToken = null; sessionStorage.removeItem('zhida-token');
        } else if (expiry - Date.now() < 5 * 60_000) {
            const refreshed = await fetch('/api/v1/auth/refresh', { method: 'POST' });
            if (refreshed.ok) {
                authToken = (await refreshed.json()).accessToken;
                sessionStorage.setItem('zhida-token', authToken);
            }
        }
    }
    let account = null;
    if (config.enabled && authToken) {
        const response = await fetch('/api/v1/auth/me');
        if (response.ok) account = await response.json();
    }
    const signedIn = !config.enabled || !!account;
    if (account) {
        const displayName = account.guest ? '游客账号' : account.username;
        accountName.textContent = displayName;
        accountHint.textContent = account.guest ? '本次会话临时身份' : '对话与资料已隔离保存';
        accountAvatar.textContent = account.guest ? '访' : account.username.slice(0, 1).toUpperCase();
        openAuthButton.hidden = true;
        logoutButton.hidden = false;
        hideAuth();
    } else if (config.enabled) {
        renderLoggedOutAccount();
        showAuth('登录或注册后继续，也可以先以游客身份体验。');
    }
    if (signedIn) {
        loadDocuments(); loadHistory(); loadMemories(); loadBases();
    }
    checkHealth();
}
function tokenExpiry(token) {
    try {
        const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
        const padded = payload.padEnd(Math.ceil(payload.length / 4) * 4, '=');
        return JSON.parse(atob(padded)).exp * 1000;
    } catch (_) { return 0; }
}
document.querySelector('#authForm').addEventListener('submit', async event => {
    event.preventDefault();
    const action = event.submitter?.value || 'login';
    const activeButton = event.submitter;
    authStatus.dataset.state = '';
    authStatus.textContent = action === 'register' ? '正在创建账号…' : '正在验证账号…';
    setAuthBusy(true, activeButton);
    try {
        const response = await window.fetch(`/api/v1/auth/${action}`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: document.querySelector('#username').value, password: document.querySelector('#password').value })
        });
        if (!response.ok) throw new Error(response.status === 409 ? '用户名已存在，请直接登录' : response.status === 401 ? '用户名或密码不正确' : response.status === 429 ? '操作过于频繁，请稍后再试' : '用户名需为 3～32 位字母、数字或下划线，密码至少 8 个字符');
        const result = await response.json();
        sessionStorage.setItem('zhida-token', result.accessToken);
        location.reload();
    } catch (error) {
        authStatus.dataset.state = 'error';
        authStatus.textContent = error.message;
        setAuthBusy(false, activeButton);
    }
});
document.querySelector('#guestLogin').addEventListener('click', async () => {
    const guestButton = document.querySelector('#guestLogin');
    authStatus.dataset.state = '';
    authStatus.textContent = '正在创建游客会话…';
    setAuthBusy(true, guestButton);
    try {
        const response = await window.fetch('/api/v1/auth/guest', { method: 'POST' });
        if (!response.ok) throw new Error('游客入口暂不可用，请稍后再试');
        sessionStorage.setItem('zhida-token', (await response.json()).accessToken);
        location.reload();
    } catch (error) {
        authStatus.dataset.state = 'error';
        authStatus.textContent = error.message;
        setAuthBusy(false, guestButton);
    }
});
openAuthButton.addEventListener('click', () => showAuth('登录或注册后继续，也可以先以游客身份体验。'));
logoutButton.addEventListener('click', () => {
    authToken = null; sessionStorage.removeItem('zhida-token'); location.reload();
});

async function loadBases() {
    const response = await fetch('/api/v1/knowledge-bases');
    if (!response.ok) return;
    const select = document.querySelector('#baseSelect');
    select.replaceChildren();
    for (const base of await response.json()) {
        const option = document.createElement('option'); option.value = base.id; option.textContent = base.name; select.append(option);
    }
    select.value = knowledgeBaseId;
}
document.querySelector('#baseSelect').addEventListener('change', async event => {
    knowledgeBaseId = event.target.value; await loadDocuments();
});
document.querySelector('#baseForm').addEventListener('submit', async event => {
    event.preventDefault();
    const input = document.querySelector('#baseName');
    const response = await fetch('/api/v1/knowledge-bases', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: input.value }) });
    if (!response.ok) { knowledgeStatus.textContent = '知识库创建失败'; return; }
    knowledgeBaseId = (await response.json()).id; input.value = ''; await loadBases(); await loadDocuments();
});

async function loadMemories() {
    const status = document.querySelector('#memoryStatus');
    try {
        const response = await fetch('/api/v1/memories');
        if (response.status === 404) return;
        document.querySelector('#memoryPanel').hidden = false;
        if (!response.ok) throw new Error('记忆暂时无法读取');
        const items = await response.json();
        const settings = await fetch('/api/v1/memories/settings');
        if (settings.ok) document.querySelector('#memoryEnabled').checked = (await settings.json()).enabled;
        const list = document.querySelector('#memoryList');
        list.replaceChildren();
        for (const item of items) {
            const row = document.createElement('div');
            row.className = 'memory-row';
            const text = document.createElement('p');
            text.textContent = item.content;
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'row-action row-action-danger';
            button.textContent = '删除';
            button.addEventListener('click', async () => {
                button.disabled = true;
                try {
                    const result = await fetch(`/api/v1/memories/${encodeURIComponent(item.id)}`, { method: 'DELETE' });
                    if (!result.ok) throw new Error('删除失败，请重试');
                    row.remove();
                    status.textContent = '已删除，之后的请求不再带入这条记忆。';
                } catch (error) { status.textContent = error.message; button.disabled = false; }
            });
            const actions = document.createElement('div'); actions.className = 'row-actions';
            const edit = document.createElement('button'); edit.type = 'button'; edit.className = 'row-action'; edit.textContent = '编辑';
            edit.addEventListener('click', () => {
                const input = document.createElement('textarea'); input.maxLength = 500; input.value = item.content;
                const save = document.createElement('button'); save.type = 'button'; save.className = 'compact-button'; save.textContent = '保存修改';
                save.addEventListener('click', async () => {
                    const result = await fetch(`/api/v1/memories/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content: input.value }) });
                    if (result.ok) await loadMemories(); else status.textContent = '修改失败，请检查内容';
                });
                row.replaceChildren(input, save);
            });
            actions.append(edit, button);
            row.append(text, actions);
            list.append(row);
        }
    } catch (error) { status.textContent = error.message; }
}
document.querySelector('#memoryEnabled').addEventListener('change', async event => {
    const response = await fetch('/api/v1/memories/settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled: event.target.checked }) });
    document.querySelector('#memoryStatus').textContent = response.ok ? (event.target.checked ? '已开启记忆参考' : '已关闭记忆参考') : '设置失败';
    if (!response.ok) event.target.checked = !event.target.checked;
});

document.querySelector('#memoryForm').addEventListener('submit', async event => {
    event.preventDefault();
    const input = document.querySelector('#memoryContent');
    const content = input.value.trim();
    if (!content) return;
    const button = event.currentTarget.querySelector('button');
    if (button.disabled) return;
    button.disabled = true;
    const status = document.querySelector('#memoryStatus');
    try {
        const response = await fetch('/api/v1/memories', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content })
        });
        if (!response.ok) throw new Error('保存失败，请重试');
        input.value = '';
        status.textContent = '已保存；开启“记忆参考”后，后续提问会使用它。';
        await loadMemories();
    } catch (error) { status.textContent = error.message; }
    finally { button.disabled = false; }
});

async function loadHistory() {
    const container = document.querySelector('#conversationHistory');
    try {
        const response = await fetch('/api/v1/conversations');
        if (response.status === 404) return; // Optional database module is disabled.
        if (!response.ok) throw new Error('聊天记录暂时无法读取');
        const conversations = await response.json();
        container.replaceChildren();
        if (conversations.length === 0) {
            const empty = document.createElement('p'); empty.className = 'empty-state'; empty.textContent = '还没有历史对话'; container.append(empty);
        }
        for (const item of conversations) {
            const row = document.createElement('div');
            row.className = 'conversation-row';
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'conversation-item';
            button.textContent = item.title;
            button.title = item.title;
            button.addEventListener('click', async () => {
                if (submitButton.disabled) return;
                submitButton.disabled = true;
                try {
                    const result = await fetch(`/api/v1/conversations/${encodeURIComponent(item.id)}`);
                    if (!result.ok) throw new Error('这段聊天记录暂时无法打开');
                    const history = await result.json();
                    conversationId = item.id;
                    messages.replaceChildren(welcome);
                    welcome.hidden = history.length > 0;
                    for (const message of history) {
                        if (message.role === 'user') appendUserMessage(message.content);
                        else {
                            createAssistantMessage();
                            answerElement.replaceChildren(renderMarkdown(message.content));
                            answerElement.classList.remove('typing');
                            thinkingDetails.hidden = true;
                        }
                    }
                    statusText.textContent = '已打开历史记录';
                    await loadTaskHistory(item.id);
                    closeSidebar();
                } catch (error) {
                    statusText.textContent = error.message;
                } finally {
                    submitButton.disabled = false;
                }
            });
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'row-action row-action-danger';
            remove.textContent = '移除';
            remove.setAttribute('aria-label', `删除会话：${item.title}`);
            remove.addEventListener('click', async () => {
                if (submitButton.disabled || !confirm('删除这段聊天及执行记录？已单独保存的记忆不会删除。')) return;
                remove.disabled = true;
                try {
                    const result = await fetch(`/api/v1/conversations/${encodeURIComponent(item.id)}`, { method: 'DELETE' });
                    if (!result.ok) throw new Error('删除失败，任务运行中时不能删除会话');
                    if (conversationId === item.id) {
                        conversationId = crypto.randomUUID(); messages.replaceChildren(welcome); welcome.hidden = false;
                    }
                    await loadHistory();
                } catch (error) { statusText.textContent = error.message; remove.disabled = false; }
            });
            row.append(button, remove);
            container.append(row);
        }
    } catch (_) {
        container.textContent = '聊天记录暂时无法读取';
    }
}

async function loadTaskHistory(id) {
    const section = document.createElement('details');
    section.className = 'thinking-card';
    const heading = document.createElement('summary');
    heading.textContent = '查看这些回答的执行记录';
    section.append(heading);
    messages.append(section);
    try {
        const response = await fetch(`/api/v1/conversations/${encodeURIComponent(id)}/tasks`);
        if (!response.ok) throw new Error('执行记录暂时无法读取');
        const tasks = await response.json();
        const labels = { RUNNING: '正在处理', COMPLETED: '已完成', FAILED: '未完成', CANCELLED: '已中断', INTERRUPTED: '意外中断' };
        for (const task of tasks) {
            const detail = document.createElement('details');
            const title = document.createElement('summary');
            title.textContent = `${labels[task.status] || task.status} · ${task.question}`;
            const content = document.createElement('div');
            detail.append(title, content);
            section.append(detail);
            let loaded = false;
            detail.addEventListener('toggle', async () => {
                if (!detail.open || loaded) return;
                loaded = true;
                content.textContent = '正在读取…';
                try {
                    const [result, usageResponse, costResponse, memoryResponse] = await Promise.all([
                        fetch(`/api/v1/conversations/${encodeURIComponent(id)}/tasks/${encodeURIComponent(task.id)}/events`),
                        fetch(`/api/v1/tasks/${encodeURIComponent(task.id)}/usage`),
                        fetch(`/api/v1/tasks/${encodeURIComponent(task.id)}/cost`),
                        fetch(`/api/v1/tasks/${encodeURIComponent(task.id)}/memories`)
                    ]);
                    if (!result.ok) throw new Error('读取失败，收起后可重试');
                    const events = await result.json();
                    content.replaceChildren();
                    if (usageResponse.ok) {
                        const calls = await usageResponse.json();
                        const usage = document.createElement('p');
                        const known = calls.filter(call => call.promptTokens != null && call.completionTokens != null);
                        const tokens = known.reduce((sum, call) => sum + call.promptTokens + call.completionTokens, 0);
                        usage.textContent = calls.length
                            ? `模型调用 ${calls.length} 次，已记录 ${tokens} Tokens${known.length < calls.length ? '（部分调用用量未知）' : ''}。`
                            : '本次没有模型用量记录（演示模式或较早的任务）。';
                        content.append(usage);
                    }
                    if (costResponse.ok) {
                        const cost = await costResponse.json();
                        if (cost.totalCalls > 0) {
                            const costSummary = document.createElement('p');
                            costSummary.className = cost.warning ? 'task-meta cost-warning' : 'task-meta';
                            if (cost.pricedCalls === 0) {
                                costSummary.textContent = '费用暂无法估算：当前记录缺少可识别的模型、Token 或调用时间。';
                            } else {
                                const amount = Number(cost.estimatedMaxCostUsd).toFixed(6);
                                const incomplete = cost.complete
                                    ? ''
                                    : `；仅覆盖 ${cost.pricedCalls}/${cost.totalCalls} 次调用`;
                                const warning = cost.warning ? '；已达到费用告警阈值' : '';
                                costSummary.textContent = `DeepSeek 费用上限估算：$${amount} USD（按缓存未命中价${incomplete}${warning}）。`;
                            }
                            content.append(costSummary);
                        }
                    }
                    if (memoryResponse.ok) {
                        const memories = await memoryResponse.json();
                        if (!memories.length) {
                            const empty = document.createElement('p');
                            empty.className = 'task-meta';
                            empty.textContent = '本次没有参考已保存记忆。';
                            content.append(empty);
                        } else {
                            const audit = document.createElement('details');
                            audit.className = 'technical-details memory-audit';
                            const auditTitle = document.createElement('summary');
                            auditTitle.textContent = `参考了 ${memories.length} 条已保存记忆（显示当前内容）`;
                            audit.append(auditTitle);
                            for (const memory of memories.slice(0, 5)) {
                                const row = document.createElement('p');
                                row.textContent = memory.deleted ? '这条记忆后来已删除' : memory.content;
                                audit.append(row);
                            }
                            content.append(audit);
                        }
                    } else {
                        const unavailable = document.createElement('p');
                        unavailable.className = 'task-meta';
                        unavailable.textContent = '记忆使用记录暂时无法读取。';
                        content.append(unavailable);
                    }
                    if (task.errorCode) {
                        const notice = document.createElement('p');
                        notice.textContent = `本次回答没有完整完成（${task.errorCode}）。`;
                        content.append(notice);
                    }
                    for (const event of events) {
                        const row = document.createElement('p');
                        if (event.type === 'plan.created') {
                            row.textContent = '已制定回答计划';
                        } else {
                            const state = event.type === 'tool.started' ? '开始' : event.type === 'tool.failed' ? '失败' : '完成';
                            row.textContent = `${toolLabel(event.data.toolName)} · ${state}`;
                            const technical = document.createElement('details');
                            const label = document.createElement('summary');
                            label.textContent = '查看详情';
                            const pre = document.createElement('pre');
                            pre.textContent = technicalText(event.data);
                            technical.append(label, pre);
                            row.append(technical);
                        }
                        content.append(row);
                    }
                    if (!events.length) content.append(document.createTextNode('没有工具调用记录。'));
                } catch (error) {
                    loaded = false;
                    content.textContent = error.message;
                }
            });
        }
        if (!tasks.length) heading.textContent = '这段历史暂时没有执行记录';
    } catch (error) {
        heading.textContent = error.message;
    }
}

document.querySelectorAll('[data-prompt]').forEach(button => {
    button.addEventListener('click', () => {
        questionInput.value = button.dataset.prompt;
        autoResizeInput();
        questionInput.focus();
    });
});

document.querySelector('#newChatButton').addEventListener('click', () => {
    if (submitButton.disabled) return;
    conversationId = crypto.randomUUID();
    messages.replaceChildren(welcome);
    welcome.hidden = false;
    questionInput.value = '';
    autoResizeInput();
    statusText.textContent = '已开启新对话';
    closeSidebar();
    questionInput.focus();
});

document.querySelector('#openSidebar').addEventListener('click', openSidebar);
document.querySelector('#closeSidebar').addEventListener('click', closeSidebar);
sidebarOverlay.addEventListener('click', closeSidebar);
questionInput.addEventListener('input', autoResizeInput);
questionInput.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        form.requestSubmit();
    }
});

documentFile.addEventListener('change', async () => {
    const file = documentFile.files?.[0];
    if (!file) return;

    documentFile.disabled = true;
    knowledgeStatus.textContent = '正在读懂这份资料。第一次使用会稍久一些…';
    const body = new FormData();
    body.append('file', file);

    try {
        const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/documents`, { method: 'POST', body });
        if (!response.ok) throw new Error(await readError(response));
        const info = await response.json();
        knowledgeStatus.textContent = info.duplicate
            ? `“${info.filename}”已经在知识库里了。`
            : `“${info.filename}”已上传，正在后台处理。`;
        await loadDocuments(false);
    } catch (error) {
        knowledgeStatus.textContent = `没有上传成功：${error.message}`;
    } finally {
        documentFile.value = '';
        documentFile.disabled = false;
    }
});

form.addEventListener('submit', async event => {
    event.preventDefault();
    const message = questionInput.value.trim();
    if (!message || submitButton.disabled) return;

    welcome.hidden = true;
    appendUserMessage(message);
    createAssistantMessage();
    questionInput.value = '';
    autoResizeInput();
    submitButton.disabled = true;
    statusText.textContent = '正在理解你的问题…';

    const controller = new AbortController();
    activeRequest = controller;
    const requestId = crypto.randomUUID();
    activeTaskId = requestId;
    stoppedByUser = false;
    stopButton.hidden = false;
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
        const response = await fetch('/api/v1/research/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
            body: JSON.stringify({ conversationId, message, knowledgeBaseId, requestId }),
            signal: controller.signal
        });
        if (response.status === 409) throw new Error('这个请求已提交或当前会话正在回答，请查看历史记录。');
        if (response.status === 429) throw new Error('已达到请求频率或今日额度限制，请稍后再试。');
        if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
        await consumeSse(response.body);
        if (answerElement.classList.contains('typing')) throw new Error('连接结束，但没有收到任务完成确认');
    } catch (error) {
        const friendlyMessage = stoppedByUser ? '已停止回答。当前未完成的回答不会保存为完整答案。' : error.name === 'AbortError'
            ? '这次回答等待时间较长，请重新提问。'
            : error.message.startsWith('这个请求') || error.message.startsWith('已达到请求频率')
                ? error.message
                : '暂时没有生成完整回答，请重新提问。';
        answerBuffer = friendlyMessage;
        answerElement.textContent = friendlyMessage;
        thinkingDetails.hidden = true;
        answerElement.classList.remove('typing');
        statusText.textContent = '可以重新试一次';
        if (!stoppedByUser) {
            try {
                const snapshot = await fetch(`/api/v1/tasks/${encodeURIComponent(requestId)}`, { signal: AbortSignal.timeout(5000) });
                if (snapshot.ok) {
                    const task = await snapshot.json();
                    const labels = { COMPLETED: '回答已保存，请从历史记录打开', RUNNING: '任务仍在执行，请稍后从历史记录查看', CANCELLED: '任务已取消', FAILED: '任务失败，可查看执行记录', INTERRUPTED: '任务已中断，可重新提问' };
                    statusText.textContent = labels[task.status] || '请查看历史记录';
                }
            } catch (_) { statusText.textContent = '暂时无法确认任务状态，请恢复连接后查看历史记录，避免重复提交。'; }
        }
    } finally {
        clearTimeout(timer);
        activeRequest = null;
        activeTaskId = null;
        stopButton.hidden = true;
        await loadHistory();
        submitButton.disabled = false;
        questionInput.focus();
    }
});

async function checkHealth() {
    try {
        const response = await fetch('/api/health');
        const health = await response.json();
        document.querySelector('#searchCapability').hidden = !health.searchEnabled;
        // 只有售后模块启用时才显示工作台入口；该入口本身不构成权限校验，售后接口仍会独立鉴权。
        const supportLink = document.querySelector('#supportLink');
        if (supportLink) supportLink.hidden = !health.supportEnabled;
        modeDot.className = `status-dot ${health.aiEnabled ? 'online' : 'demo'}`;
        if (!health.aiEnabled) modeBadge.textContent = '演示模式';
        else if (health.searchEnabled) modeBadge.textContent = 'Agent 与联网搜索可用';
        else modeBadge.textContent = 'Agent 可用 · 未开启联网';
    } catch (_) {
        modeDot.className = 'status-dot offline';
        modeBadge.textContent = '服务暂时不可用';
    }
}

async function loadDocuments(updateMessage = true) {
    clearTimeout(documentRefreshTimer);
    documentRefreshTimer = undefined;
    try {
        const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/documents`);
        if (!response.ok) return;
        const documents = await response.json();
        const processingCount = documents.filter(item => item.status === 'PROCESSING').length;
        const deletingCount = documents.filter(item => item.status === 'DELETING').length;
        const failedCount = documents.filter(item => item.status === 'FAILED').length;
        const documentList = document.querySelector('#documentList');
        documentList.replaceChildren();
        if (documents.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'empty-state';
            empty.textContent = '这里还没有资料';
            documentList.append(empty);
        }
        for (const item of documents) {
            const row = document.createElement('div');
            row.className = 'document-row';

            const meta = document.createElement('span');
            meta.className = 'document-meta';

            const label = document.createElement('strong');
            label.textContent = item.filename;
            const status = item.status || 'READY';
            const state = document.createElement('small');
            state.className = `document-state ${status.toLowerCase()}`;
            state.textContent = DOCUMENT_STATUS_LABELS[status] || status;
            if (item.errorMessage) {
                state.title = item.errorMessage;
            }
            meta.append(label, state);

            const actions = document.createElement('span');
            actions.className = 'document-actions';
            if (status === 'FAILED') {
                const retry = document.createElement('button');
                retry.type = 'button';
                retry.className = 'row-action';
                retry.textContent = '重试';
                retry.addEventListener('click', async () => {
                    retry.disabled = true;
                    const result = await fetch(`/api/v1/knowledge-bases/${encodeURIComponent(item.knowledgeBaseId)}/documents/${encodeURIComponent(item.id)}/retry`, { method: 'POST' });
                    knowledgeStatus.textContent = result.ok ? `正在重新处理“${item.filename}”。` : `重试失败：${await readError(result)}`;
                    await loadDocuments(false);
                });
                actions.append(retry);
            }

            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'row-action row-action-danger';
            remove.textContent = status === 'DELETING' ? '继续删除' : '移除';
            remove.setAttribute('aria-label', `删除资料：${item.filename}`);
            remove.disabled = status === 'PROCESSING';
            if (remove.disabled) {
                remove.title = '处理完成后才能删除';
            }
            remove.addEventListener('click', async () => {
                if (!window.confirm(`删除“${item.filename}”及其检索索引？`)) return;
                remove.disabled = true;
                const result = await fetch(`/api/v1/knowledge-bases/${encodeURIComponent(item.knowledgeBaseId)}/documents/${encodeURIComponent(item.id)}`, { method: 'DELETE' });
                if (result.ok) {
                    await loadDocuments();
                } else {
                    remove.disabled = false;
                    knowledgeStatus.textContent = `删除未完成：${await readError(result)}`;
                }
            });
            actions.append(remove);
            row.append(meta, actions);
            documentList.append(row);
        }
        documentCount.textContent = String(documents.length);
        if (processingCount > 0 || deletingCount > 0) {
            knowledgeStatus.textContent = processingCount > 0
                ? `${processingCount} 份资料正在后台处理，完成后即可检索。`
                : `${deletingCount} 份资料正在继续删除。`;
            documentRefreshTimer = window.setTimeout(() => loadDocuments(), 1500);
        } else if (failedCount > 0) {
            knowledgeStatus.textContent = `${failedCount} 份资料处理失败，可以点击重试。`;
        } else if (updateMessage && documents.length > 0) {
            const names = documents.slice(0, 2).map(item => item.filename).join('、');
            knowledgeStatus.textContent = `已准备好：${names}${documents.length > 2 ? ' 等' : ''}`;
        }
    } catch (_) {
        documentCount.textContent = '?';
    }
}

async function consumeSse(body) {
    const reader = body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
        const blocks = buffer.split(/\r?\n\r?\n/);
        buffer = blocks.pop() || '';
        for (const block of blocks) {
            const dataLines = block.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trim());
            if (dataLines.length) handleAgentEvent(JSON.parse(dataLines.join('\n')));
        }
        if (done) break;
    }
}

function handleAgentEvent(event) {
    const type = event.type;
    const data = event.data || {};
    if (type === 'task.started') {
        statusText.textContent = '正在分析…';
        thinkingSummary.textContent = '正在分析你的问题';
        addThinkingItem('先理解你的问题', '确认你真正想解决什么，避免答非所问。', 'active');
    } else if (type === 'plan.created') {
        planTotal = data.steps?.length || 0;
        renderFriendlyPlan(data.steps || []);
        thinkingSummary.textContent = `已经想好怎么回答 · ${planTotal} 个小步骤`;
    } else if (type === 'answer.started') {
        statusText.textContent = '正在查找和整理…';
    } else if (type === 'answer.delta') {
        answerBuffer += data.content || '';
        answerElement.textContent = answerBuffer;
        scrollMessages();
    } else if (type === 'task.completed') {
        answerElement.replaceChildren(renderMarkdown(answerBuffer));
        finishThinking('已完成分析', true);
        statusText.textContent = '回答完成';
        scrollMessages();
    } else if (type === 'task.failed') {
        if (!answerBuffer.trim()) answerBuffer = '暂时没有生成完整回答，请重新提问。';
        answerElement.replaceChildren(renderMarkdown(answerBuffer));
        thinkingDetails.hidden = true;
        answerElement.classList.remove('typing');
        statusText.textContent = '回答未完成，请重试';
    }
}

function createAssistantMessage() {
    answerBuffer = '';
    planTotal = 0;
    const article = document.createElement('article');
    article.className = 'message assistant-message';
    const avatar = document.createElement('div');
    avatar.className = 'assistant-avatar';
    avatar.textContent = '知';
    const body = document.createElement('div');
    body.className = 'assistant-body';
    thinkingDetails = document.createElement('details');
    thinkingDetails.className = 'thinking-card';
    thinkingDetails.open = true;
    thinkingSummary = document.createElement('summary');
    thinkingSummary.textContent = '准备分析问题';
    thinkingList = document.createElement('div');
    thinkingList.className = 'thinking-list';
    thinkingDetails.append(thinkingSummary, thinkingList);
    answerElement = document.createElement('div');
    answerElement.className = 'answer-content typing';
    body.append(thinkingDetails, answerElement);
    article.append(avatar, body);
    messages.appendChild(article);
    scrollMessages();
}

function appendUserMessage(content) {
    const article = document.createElement('article');
    article.className = 'message user-message';
    const bubble = document.createElement('div');
    bubble.textContent = content;
    article.appendChild(bubble);
    messages.appendChild(article);
    scrollMessages();
}

function renderFriendlyPlan(steps) {
    thinkingList.innerHTML = '';
    steps.forEach((step, index) => addThinkingItem(
        `${index + 1}. ${friendlyStepTitle(step.stepId, step.title)}`,
        friendlyStepDescription(step.stepId, step.goal),
        index === 0 ? 'done' : 'pending'
    ));
}

function addThinkingItem(title, description, state = 'pending', technicalData) {
    const item = document.createElement('div');
    item.className = `thinking-item ${state}`;
    const marker = document.createElement('span');
    marker.className = 'thinking-marker';
    marker.textContent = state === 'done' ? '✓' : state === 'error' ? '!' : '';
    const content = document.createElement('div');
    const strong = document.createElement('strong');
    strong.textContent = title;
    const text = document.createElement('p');
    text.textContent = description;
    content.append(strong, text);
    if (technicalData) {
        const technical = document.createElement('details');
        technical.className = 'technical-details';
        const summary = document.createElement('summary');
        summary.textContent = '查看技术细节';
        const pre = document.createElement('pre');
        pre.textContent = technicalText(technicalData);
        technical.append(summary, pre);
        content.appendChild(technical);
    }
    item.append(marker, content);
    thinkingList.appendChild(item);
    scrollMessages();
    return item;
}

function technicalText(data) {
    return [
        `工具：${data.toolName || '未知'}`,
        data.arguments ? `参数：${data.arguments}` : '',
        Number.isFinite(data.durationMs) ? `耗时：${data.durationMs} ms` : '',
        data.resultPreview ? `结果摘要：${data.resultPreview}` : ''
    ].filter(Boolean).join('\n');
}

function finishThinking(text, success) {
    if (success) {
        thinkingList.querySelectorAll('.thinking-item.pending, .thinking-item.active').forEach(item => {
            item.className = 'thinking-item done';
            item.querySelector('.thinking-marker').textContent = '✓';
        });
    }
    thinkingSummary.textContent = success ? `${text} · 点击查看过程` : text;
    thinkingDetails.classList.toggle('failed', !success);
    thinkingDetails.open = !success;
    answerElement.classList.remove('typing');
}

function friendlyStepTitle(stepId, fallback) {
    const labels = {
        understand: '先弄清楚你想问什么', search: '查找最新资料', retrieve: '查找相关资料',
        'retrieve-document': '从你的文档里找答案', compare: '把不同方案放在一起比较', compose: '整理成容易理解的答案'
    };
    return labels[stepId] || fallback || '处理这个问题';
}

function friendlyStepDescription(stepId, fallback) {
    const labels = {
        understand: '先确认目标和重点，减少误解。', search: '寻找较新、较可靠并且能够核对的信息。',
        retrieve: '收集与问题直接相关的内容。', 'retrieve-document': '检索上传过的资料，并保留文件出处。',
        compare: '看清各自的优点、缺点和适用情况。', compose: '把结论、理由和下一步建议说清楚。'
    };
    return labels[stepId] || fallback || '完成必要的信息整理。';
}

function toolLabel(toolName) {
    return ({ current_date: '确认日期', web_search: '联网搜索', read_web_page: '阅读网页', knowledge_search: '查找知识库' })[toolName] || toolName || '未知工具';
}

function renderMarkdown(markdown) {
    const fragment = document.createDocumentFragment();
    const lines = markdown.replace(/\r\n/g, '\n').split('\n');
    let list;
    let codeBlock;
    const closeList = () => { list = null; };
    for (let index = 0; index < lines.length; index++) {
        const rawLine = lines[index];
        const line = rawLine.trimEnd();
        if (line.startsWith('```')) {
            closeList();
            if (codeBlock) { fragment.appendChild(codeBlock); codeBlock = null; }
            else codeBlock = document.createElement('pre');
            continue;
        }
        if (codeBlock) { codeBlock.textContent += `${rawLine}\n`; continue; }
        if (!line.trim()) { closeList(); continue; }
        if (/^---+$/.test(line.trim())) { closeList(); fragment.appendChild(document.createElement('hr')); continue; }
        if (line.includes('|') && lines[index + 1] && isTableDivider(lines[index + 1])) {
            closeList();
            const table = document.createElement('table');
            const head = document.createElement('thead');
            const headRow = document.createElement('tr');
            splitTableRow(line).forEach(cellText => {
                const cell = document.createElement('th');
                appendInline(cell, cellText);
                headRow.appendChild(cell);
            });
            head.appendChild(headRow);
            table.appendChild(head);
            const body = document.createElement('tbody');
            index += 2;
            while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
                const row = document.createElement('tr');
                splitTableRow(lines[index]).forEach(cellText => {
                    const cell = document.createElement('td');
                    appendInline(cell, cellText);
                    row.appendChild(cell);
                });
                body.appendChild(row);
                index++;
            }
            index--;
            table.appendChild(body);
            fragment.appendChild(table);
            continue;
        }
        const headingMatch = line.match(/^(#{1,3})\s+(.+)$/);
        if (headingMatch) {
            closeList();
            const heading = document.createElement(`h${Math.min(headingMatch[1].length + 1, 4)}`);
            appendInline(heading, headingMatch[2]);
            fragment.appendChild(heading);
            continue;
        }
        const unordered = line.match(/^[-*]\s+(.+)$/);
        const ordered = line.match(/^\d+[.)]\s+(.+)$/);
        if (unordered || ordered) {
            const tag = ordered ? 'ol' : 'ul';
            if (!list || list.tagName.toLowerCase() !== tag) { list = document.createElement(tag); fragment.appendChild(list); }
            const item = document.createElement('li');
            appendInline(item, (unordered || ordered)[1]);
            list.appendChild(item);
            continue;
        }
        closeList();
        const paragraph = document.createElement(line.startsWith('> ') ? 'blockquote' : 'p');
        appendInline(paragraph, line.startsWith('> ') ? line.slice(2) : line);
        fragment.appendChild(paragraph);
    }
    if (codeBlock) fragment.appendChild(codeBlock);
    return fragment;
}

function isTableDivider(line) {
    return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);
}

function splitTableRow(line) {
    return line.trim().replace(/^\||\|$/g, '').split('|').map(cell => cell.trim());
}

function appendInline(parent, text) {
    const pattern = /(\[[^\]]+\]\(https?:\/\/[^\s)]+\)|https?:\/\/[^\s<>'"`]+|\*\*[^*]+\*\*|`[^`]+`)/g;
    let cursor = 0;
    for (const match of text.matchAll(pattern)) {
        parent.appendChild(document.createTextNode(text.slice(cursor, match.index)));
        const token = match[0];
        if (token.startsWith('[')) {
            const parts = token.match(/^\[([^\]]+)]\((https?:\/\/[^)]+)\)$/);
            const link = document.createElement('a');
            link.href = parts[2];
            link.textContent = parts[1];
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            parent.appendChild(link);
        } else if (token.startsWith('http')) {
            let url = token;
            let punctuation = '';
            while (/[),.;，。；：）\]]$/.test(url)) { punctuation = url.slice(-1) + punctuation; url = url.slice(0, -1); }
            const link = document.createElement('a');
            link.href = url;
            link.textContent = url;
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            parent.append(link, document.createTextNode(punctuation));
        } else if (token.startsWith('**')) {
            const strong = document.createElement('strong');
            strong.textContent = token.slice(2, -2);
            parent.appendChild(strong);
        } else {
            const code = document.createElement('code');
            code.textContent = token.slice(1, -1);
            parent.appendChild(code);
        }
        cursor = match.index + token.length;
    }
    parent.appendChild(document.createTextNode(text.slice(cursor)));
}

async function readError(response) {
    const body = await response.json().catch(() => null);
    return body?.detail || body?.message || `HTTP ${response.status}`;
}

function autoResizeInput() {
    questionInput.style.height = 'auto';
    questionInput.style.height = `${Math.min(questionInput.scrollHeight, 180)}px`;
}

function scrollMessages() {
    requestAnimationFrame(() => messages.scrollTo({ top: messages.scrollHeight, behavior: 'smooth' }));
}

function openSidebar() { sidebar.classList.add('open'); sidebarOverlay.classList.add('visible'); }
function closeSidebar() { sidebar.classList.remove('open'); sidebarOverlay.classList.remove('visible'); }
