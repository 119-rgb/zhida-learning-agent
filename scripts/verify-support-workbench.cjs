/*
 * 模块 5 浏览器验收。运行前启动一套显式启用 demo-data 的隔离服务，并通过环境变量传入
 * 临时演示密码。脚本不写截图、Token、密码或日志文件，只在标准输出报告断言结果。
 *
 * 密码环境变量名必须与应用绑定的属性一致：
 *   zhida.support.demo-data.password  ->  ZHIDA_SUPPORT_DEMO_DATA_PASSWORD
 * 兼容读取旧的 ZHIDA_SUPPORT_DEMO_PASSWORD 只是为了避免旧命令直接报错，正式文档只写新名字。
 */
const { randomUUID } = require('crypto');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');

const baseUrl = process.env.ZHIDA_BROWSER_BASE_URL || 'http://127.0.0.1:18080';
const password = process.env.ZHIDA_SUPPORT_DEMO_DATA_PASSWORD || process.env.ZHIDA_SUPPORT_DEMO_PASSWORD;
if (!password) {
  throw new Error('缺少 ZHIDA_SUPPORT_DEMO_DATA_PASSWORD（与 zhida.support.demo-data.password 对应）');
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function login(browser, username) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`${baseUrl}/support.html`, { waitUntil: 'networkidle' });
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('button[value="login"]').click();
  await page.locator('#nav .nav-item').first().waitFor();
  return { context, page };
}

async function clickRoute(page, route) {
  await page.locator(`[data-route="${route}"]`).click();
  await page.waitForURL(url => url.hash === `#${route}`);
  await page.locator('.loading').waitFor({ state: 'detached' }).catch(() => {});
}

/**
 * 等待操作按钮可交互后再点击；页面在每次操作后会异步重建操作面板。
 * 超时时打印面板实际内容，便于区分“渲染延迟”和“状态或权限不符”。
 */
async function clickActionOn(page, name) {
  await waitForAction(page, name);
  await page.locator(`[data-act="${name}"]`).click();
}

async function waitForAction(page, name, timeout = 15000) {
  const button = page.locator(`[data-act="${name}"]`);
  try {
    await button.waitFor({ state: 'visible', timeout });
  } catch (error) {
    const panel = await page.locator('#actionPanel').innerText().catch(() => '(无操作面板)');
    const tags = await page.locator('.status-tag').allInnerTexts().catch(() => []);
    throw new Error(
      `操作按钮 ${name} 不可用；url=[${page.url()}] 面板=[${panel.replace(/\n+/g, ' | ')}] 状态=[${tags.join(',')}]；原因=${error.message.split('\n')[0]}`
    );
  }
}

/**
 * 直接轮询后端工单状态。页面文案既可能来自历史事件记录，也可能滞后于服务端，
 * 因此状态类断言一律以接口返回为准，避免把“流程没走完”误判成通过。
 */
async function expectTicketStatus(page, ticketId, expected, timeout = 15000) {
  await page.waitForFunction(
    async ([id, want]) => {
      const token = sessionStorage.getItem('zhida-token');
      const response = await fetch(`/api/v1/support/tickets/${id}`, { headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) return false;
      return (await response.json()).ticket.status === want;
    },
    [ticketId, expected],
    { timeout }
  );
}

/**
 * 打开指定工单详情并等待渲染完成。
 *
 * <p>这里不能直接用 goto(ticketUrl)：当目标地址与当前地址只差 hash 时，goto 属于同文档导航，
 * 浏览器不会重新加载文档，页面会继续显示上一条路由（可能是另一张工单）的内容。
 * 所以先把 hash 设为目标值，再 reload 强制重新加载，确保读到服务端最新状态。
 */
async function openTicket(page, ticketUrl, ticketId) {
  if (page.url() !== ticketUrl) {
    await page.evaluate(target => { window.location.hash = target.split('#')[1]; }, ticketUrl);
  }
  await page.reload({ waitUntil: 'networkidle' });
  await page.getByText(`工单 ${ticketId.slice(0, 8)}`, { exact: false }).first().waitFor({ timeout: 15000 });
}

/** 详情页状态标签的文案，用来确认页面渲染的就是期望状态，而不是列表页或其他工单。 */
const STATUS_TAG_TEXT = {
  PENDING: '待受理',
  PROCESSING: '处理中',
  AWAITING_CONFIRMATION: '待用户确认',
  CLOSED: '已关闭'
};

/**
 * 等待详情页渲染出期望状态。
 *
 * <p>只等按钮会在「后端已是目标状态、页面还没刷新完」时超时；只等后端状态又会在页面其实停在
 * 别的路由时误判。两个条件都确认，失败时才能区分是渲染慢还是流程没走完。
 */
async function expectRenderedStatus(page, expectedStatus, timeout = 15000) {
  const label = STATUS_TAG_TEXT[expectedStatus];
  await page.locator('.status-tag').filter({ hasText: label }).first().waitFor({ state: 'visible', timeout });
}

/**
 * 稳定的详情操作入口：后端状态与页面渲染状态都符合期望，才执行操作。
 *
 * <p>偶发的页面渲染未完成（例如会话恢复较慢）会让单次等待超时，但后端状态其实已经正确。
 * 这里把「后端状态」作为事实来源，页面没跟上就重新打开详情重试；重试前后端状态若已变化，
 * 直接报出真实原因，避免用重试掩盖状态冲突。
 *
 * @param options.fill      打开详情后、点击前要先填写的表单字段
 * @param options.afterOpen 打开详情后、点击前要执行的自定义准备动作（例如选择评分）
 */
async function actOnTicket(page, ticketUrl, ticketId, expectedStatus, action, options = {}, attempts = 3) {
  const { fill, afterOpen } = options;
  await expectTicketStatus(page, ticketId, expectedStatus);
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    await openTicket(page, ticketUrl, ticketId);
    try {
      await expectRenderedStatus(page, expectedStatus);
      if (afterOpen) await afterOpen(page);
      if (fill) await page.locator(fill.selector).fill(fill.value);
      await waitForAction(page, action, 8000);
      await page.locator(`[data-act="${action}"]`).click();
      return;
    } catch (error) {
      lastError = error;
      // 重试前再确认后端状态没有被并发改变，否则重试只会掩盖真实冲突。
      await expectTicketStatus(page, ticketId, expectedStatus).catch(() => {
        throw new Error(`重试前后端状态已不是 ${expectedStatus}：${error.message.split('\n')[0]}`);
      });
    }
  }
  throw lastError;
}

async function authorizedJson(page, path) {
  return page.evaluate(async target => {
    const token = sessionStorage.getItem('zhida-token');
    const response = await fetch(target, { headers: { Authorization: `Bearer ${token}` } });
    if (!response.ok) throw new Error(`${target} -> ${response.status}`);
    return response.json();
  }, path);
}

/**
 * 走后端真实 SSE 接口做契约自检。
 *
 * <p>下面的页面用例用 page.route 回放构造的 SSE 事件，只能证明“页面能正确处理这些事件”，
 * 不能证明“后端真的会发出这些事件”。这个探针直接请求售后助手接口（Demo 模式不调用模型），
 * 断言响应体是合法 SSE 且包含完整的任务事件序列，从而覆盖「后端投影事件完全消失」的缺口。
 * 需要真实模型才能产生草稿/出处事件，因此这里只校验通用事件契约。
 */
async function assertAssistantStreamContract(page) {
  const body = await page.evaluate(async () => {
    const token = sessionStorage.getItem('zhida-token');
    const response = await fetch('/api/v1/support/assistant/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ message: '我的订单已经付款，但服务没有开通', conversationId: 'browser-contract-probe' })
    });
    if (response.status === 429) {
      // 应用自带每 IP 限流（其他 POST 每分钟 30 次）。连续跑多次脚本会触发，
      // 这不是功能缺陷，明确报出来以免被误读。
      throw new Error('请求被限流（429）：稍等一分钟再跑，或不要连续重复执行本脚本');
    }
    if (!response.ok) throw new Error(`assistant/stream -> ${response.status}`);
    return response.text();
  });
  const events = [...body.matchAll(/^event:([a-z.]+)$/gm)].map(match => match[1]);
  assert(events.length > 0, '售后助手 SSE 没有任何事件');
  for (const required of ['task.started', 'plan.created', 'answer.started', 'answer.delta', 'task.completed']) {
    assert(events.includes(required), `售后助手 SSE 缺少事件 ${required}（实际：${events.join(',')}）`);
  }
  assert(!events.includes('task.failed'), `售后助手 Demo 模式不应失败（实际：${events.join(',')}）`);
}

function sseEvent(type, data, sequence) {
  return `event:${type}\ndata:${JSON.stringify({ sequence, taskId: 'browser-fixture', type, timestamp: new Date().toISOString(), data })}\n\n`;
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const opened = [];
  try {
    const user = await login(browser, 'zhida_demo_user');
    opened.push(user.context);
    assert(await user.page.locator('[data-route="orders"]').isVisible(), '用户端缺少我的订单入口');
    assert(await user.page.locator('[data-route="newTicket"]').isVisible(), '用户端缺少手动建单入口');

    await clickRoute(user.page, 'orders');
    await user.page.getByText('DEMO-', { exact: false }).first().waitFor();
    await user.page.getByText('已付款', { exact: true }).waitFor();
    await user.page.getByText('未开通', { exact: true }).waitFor();

    const [orders, categories] = await Promise.all([
      authorizedJson(user.page, '/api/v1/support/orders'),
      authorizedJson(user.page, '/api/v1/support/categories')
    ]);
    assert(orders.length === 1, '隔离演示库应有一张用户订单');
    assert(categories.length === 1, '隔离演示库应有一个启用分类');

    // 先走后端真实 SSE 契约自检，避免下面的构造事件回放掩盖“后端不再发事件”的故障。
    await assertAssistantStreamContract(user.page);

    const draftRequestId = randomUUID();
    const longDescription = `虚构场景：订单已经付款，但服务没有开通。${'补充现象。'.repeat(180)}`;
    // 以下回放只覆盖“页面如何处理这些事件”（长草稿、结构化出处、依据不足提示）；
    // 后端是否真的发出事件由上面的 assertAssistantStreamContract 与 docs 里记录的真实模型验证负责。
    await user.page.route('**/api/v1/support/assistant/stream', async route => {
      // 只回放页面真正消费的事件；不再注入已废弃的 tool.completed + resultPreview 摘要，
      // 那会让脚本看起来在验证审计摘要，实际页面已改为消费结构化投影事件。
      const body = [
        sseEvent('answer.started', {}, 1),
        sseEvent('tool.completed', { toolName: 'knowledge_search' }, 2),
        sseEvent('support.knowledge.evidence', {
          evidenceSufficient: true,
          message: '命中 1 个片段',
          nextAction: null,
          sources: [{ filename: 'fictional-product-support.md', pageNumber: null, chunkIndex: 1, excerpt: '已付款但未开通时，请提交售后工单并关联订单。' }]
        }, 3),
        sseEvent('answer.delta', { content: '订单状态为已付款、未开通。依据如下，建议创建工单。' }, 4),
        sseEvent('tool.completed', { toolName: 'support_ticket_draft' }, 5),
        sseEvent('support.ticket-draft.ready', { draft: {
          requestId: draftRequestId,
          title: '订单已付款但服务未开通',
          description: longDescription,
          categoryId: categories[0].id,
          categoryName: categories[0].name,
          orderId: orders[0].id,
          confirmed: false,
          nextAction: '请用户确认'
        } }, 6),
        sseEvent('task.completed', {}, 7)
      ].join('');
      await route.fulfill({ status: 200, contentType: 'text/event-stream; charset=utf-8', body });
    });

    await clickRoute(user.page, 'assistant');
    assert(await user.page.locator('#manualFallback').isVisible(), '模型不可用时的手动入口不可见');
    await user.page.locator('#assistantInput').fill('我的订单已经付款，但服务没有开通');
    await user.page.locator('#assistantSend').click();
    // 回归断言：状态文字用 textContent 更新，若按钮与文字共用同一节点会被整体抹掉。
    // 这里在“发送之后”再确认一次，确保模型失败降级时手动建单入口仍然可用。
    assert(
      await user.page.locator('#manualFallback').isVisible(),
      '发送消息后手动建单入口消失（状态文字覆盖了按钮节点）'
    );
    await user.page.getByText('可核对出处', { exact: true }).waitFor();
    await user.page.getByText('fictional-product-support.md', { exact: false }).waitFor();
    await user.page.locator('#draftModal:not([hidden])').waitFor();
    assert((await user.page.locator('#draftBody').innerText()).includes(longDescription), '长草稿在页面事件中被截断');
    await user.page.locator('#draftConfirm').click();
    await user.page.waitForURL(url => url.hash.startsWith('#ticket/'));
    const ticketUrl = user.page.url();
    // 从 URL 取出工单 ID，后续所有状态断言都直接读后端，不依赖页面文案。
    const ticketId = ticketUrl.split('#ticket/')[1];
    assert(ticketId, '确认草稿后没有跳转到工单详情');
    await expectRenderedStatus(user.page, 'PENDING');

    const agent = await login(browser, 'zhida_demo_agent');
    opened.push(agent.context);
    assert(await agent.page.locator('[data-route="pending"]').isVisible(), '客服端缺少待受理队列');
    assert(!(await agent.page.locator('[data-route="assistant"]').count()), '客服端不应展示用户售后助手');
    await clickRoute(agent.page, 'pending');
    // 待受理队列断言：本工单必须出现在队列里（而不是靠点击第一行来"选中"它）。
    await agent.page.locator('.ticket-row').filter({ hasText: ticketId.slice(0, 8) }).first().waitFor();
    // 用主流程创建的 ticketId 直接打开详情：队列里可能存在标题相同的其它演示工单，
    // 按标题取第一个会操作到别的工单，导致后续所有状态断言看错对象。
    await openTicket(agent.page, `${baseUrl}/support.html#ticket/${ticketId}`, ticketId);

    // 客服操作统一走 actOnTicket：先确认后端状态，再等页面渲染出同一状态，最后点击。
    // 早期版本用「等按钮可见再点」，会在页面重新渲染替换节点时出现 30 秒点击超时，
    // 失败信息也看不出是渲染慢还是流程没走通。
    const agentTicketUrl = `${baseUrl}/support.html#ticket/${ticketId}`;
    await actOnTicket(agent.page, agentTicketUrl, ticketId, 'PENDING', 'claim');

    await actOnTicket(agent.page, agentTicketUrl, ticketId, 'PROCESSING', 'reply', {
      fill: { selector: '#replyText', value: '正在核对虚构订单与订阅开通记录。' }
    });

    await actOnTicket(agent.page, agentTicketUrl, ticketId, 'PROCESSING', 'solution', {
      fill: { selector: '#solutionText', value: '虚构方案：重新同步订阅状态后，请退出并重新登录。' }
    });
    await expectTicketStatus(agent.page, ticketId, 'AWAITING_CONFIRMATION');

    await actOnTicket(user.page, ticketUrl, ticketId, 'AWAITING_CONFIRMATION', 'reopen', {
      fill: { selector: '#commentText', value: '按方案操作后仍未开通，请继续处理。' }
    });
    await expectTicketStatus(user.page, ticketId, 'PROCESSING');

    await actOnTicket(agent.page, agentTicketUrl, ticketId, 'PROCESSING', 'solution', {
      fill: { selector: '#solutionText', value: '虚构方案：已完成第二次同步，请再次登录核对。' }
    });
    await expectTicketStatus(agent.page, ticketId, 'AWAITING_CONFIRMATION');

    await actOnTicket(user.page, ticketUrl, ticketId, 'AWAITING_CONFIRMATION', 'confirm', {
      afterOpen: async page => {
        await page.locator('#rateScore').selectOption('5');
        await page.locator('#rateText').fill('虚构评价：第二次同步后已恢复。');
      }
    });
    await expectTicketStatus(user.page, ticketId, 'CLOSED');
    await user.page.getByText('5 分', { exact: false }).waitFor();

    await clickRoute(user.page, 'newTicket');
    await user.page.locator('#manualTitle').fill('手动路径演示工单');
    await user.page.locator('#manualDescription').fill('模型不可用时通过表单直接提交的虚构问题。');
    await user.page.locator('#manualOrder').selectOption(orders[0].id);
    await user.page.locator('#manualTicketForm button[type="submit"]').click();
    await user.page.waitForURL(url => url.hash.startsWith('#ticket/'));
    await user.page.getByText('手动路径演示工单', { exact: true }).waitFor();

    // 主动造草稿入口：真实模型很少自己调用草稿工具，所以页面必须能直接生成草稿。
    // 这一步在第二次助手会话里做：先确认卡片（弹窗）在模型事件后自动关闭，再用页面入口生成草稿，
    // 最后取消并断言「没确认就不会产生工单」。
    await clickRoute(user.page, 'assistant');
    await user.page.locator('#assistantInput').fill('服务没有开通，请帮我建单');
    await user.page.locator('#assistantSend').click();
    await user.page.getByText('可核对出处', { exact: true }).waitFor();
    await user.page.locator('#draftCancel').click();
    await user.page.locator('#draftModal').waitFor({ state: 'hidden', timeout: 15000 });

    const beforeDraftTickets = await authorizedJson(user.page, '/api/v1/support/tickets?view=mine');
    // 必须先等本轮回答结束再点「生成工单草稿」：描述取的是本轮对话内容，
    // 流还没结束时助手回答尚未填充，会生成空描述的草稿。
    await user.page.locator('#assistantDraft').click();
    await user.page.locator('#draftCompose:not([hidden])').waitFor();
    // 分类与订单是异步拉取的：等 select 真正有选中值再断言。
    // 注意不能用 option 的可见性判断——收起的 <select> 里 option 永远不可见。
    await user.page.waitForFunction(
      () => {
        const category = document.getElementById('draftCategory');
        const order = document.getElementById('draftOrder');
        return category && category.value && order && order.options.length > 0;
      },
      undefined,
      { timeout: 15000 }
    );
    assert(
      (await user.page.locator('#draftCategory option').count()) === 1,
      '草稿表单的分类下拉没有来自服务端的分类'
    );
    assert(
      (await user.page.locator('#draftOrder option').count()) === 2,
      '草稿表单的订单下拉应包含“不关联订单”和用户本人订单'
    );
    assert(
      (await user.page.locator('#draftDescription').inputValue()).includes('问题：服务没有开通，请帮我建单'),
      '草稿描述没有带入本次对话的问题'
    );
    await user.page.locator('#draftComposeSubmit').click();
    await user.page.locator('#draftModal:not([hidden])').waitFor();
    assert(
      (await user.page.locator('#draftBody').innerText()).includes('服务没有开通，请帮我建单'),
      '页面主动生成的草稿内容不正确'
    );
    // 取消草稿：未确认就不得建单，工单数量必须保持不变。
    await user.page.locator('#draftCancel').click();
    await user.page.locator('#draftModal').waitFor({ state: 'hidden', timeout: 15000 });
    const afterDraftTickets = await authorizedJson(user.page, '/api/v1/support/tickets?view=mine');
    assert(
      afterDraftTickets.length === beforeDraftTickets.length,
      '取消草稿后不应产生新工单'
    );
    // 取消后仍留在助手页，输入区恢复可用：说明弹窗与造草稿表单都没有卡住页面。
    assert(await user.page.locator('#draftCompose').isHidden(), '取消后造草稿表单应已收起');
    assert(await user.page.locator('#assistantSend').isEnabled(), '取消后发送按钮应恢复可用');

    // 文档状态自动刷新：用受控响应让文档先处于「处理中」再变为「已就绪」，
    // 期间不做任何页面刷新，验证前端轮询能自己更新状态（后台处理是异步的，这原本要手动刷新）。
    let documentPolls = 0;
    await user.page.route('**/api/v1/knowledge-bases/*/documents', async route => {
      if (route.request().method() !== 'GET') return route.continue();
      documentPolls += 1;
      const status = documentPolls === 1 ? 'PROCESSING' : 'READY';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { id: 'browser-doc-1', filename: 'fictional-product-support.md', status, size: 2048, chunkCount: status === 'READY' ? 3 : null }
        ])
      });
    });
    await clickRoute(user.page, 'knowledge');
    await user.page.getByText('处理中', { exact: true }).first().waitFor({ timeout: 15000 });
    // 不刷新页面：轮询应在若干次请求内把状态换成「已就绪」。
    await user.page.getByText('已就绪', { exact: true }).first().waitFor({ timeout: 20000 });
    assert(documentPolls >= 2, `文档状态轮询没有重新请求（请求次数 ${documentPolls}）`);
    // 完成后停止轮询：再等一段时间不应继续增加请求。
    const pollsAfterReady = documentPolls;
    await user.page.waitForTimeout(5000);
    assert(
      documentPolls === pollsAfterReady,
      `文档就绪后不应继续轮询（${pollsAfterReady} -> ${documentPolls}）`
    );
    await user.page.unroute('**/api/v1/knowledge-bases/*/documents');

    // 会话失效恢复：token 只在页面加载时读入内存变量，所以要写入无效 token 后重载页面，
    // 模拟"本地还留着已过期的 JWT"。此时接口返回 401，页面必须回到登录态并清掉失效 token；
    // 若只在代码里替换 sessionStorage 而不重载，请求带的仍是内存中的有效 token，测不出问题。
    await user.page.evaluate(() => sessionStorage.setItem('zhida-token', 'invalid.expired.token'));
    await user.page.reload({ waitUntil: 'networkidle' });
    await user.page.locator('#authModal:not([hidden])').waitFor({ timeout: 15000 });
    assert(
      (await user.page.locator('#authStatus').innerText()).includes('登录状态已过期'),
      '会话过期后应提示重新登录'
    );
    assert(
      !(await user.page.evaluate(() => sessionStorage.getItem('zhida-token'))),
      '会话过期后应清除失效 token'
    );
    // 重新登录，然后用"只让助手接口返回 401"的方式单独验证流式请求那条分支：
    // 它绕过了 api()，必须自己处理 401。其余请求（身份/工单/知识库）保持正常，
    // 模拟"本地 token 看起来还有效、但服务端已判定过期"的真实场景。
    await user.page.locator('#username').fill('zhida_demo_user');
    await user.page.locator('#password').fill(password);
    await user.page.locator('button[value="login"]').click();
    await user.page.locator('#nav .nav-item').first().waitFor();

    await clickRoute(user.page, 'assistant');
    await user.page.route('**/api/v1/support/assistant/stream', route =>
      route.fulfill({ status: 401, contentType: 'application/json', body: '{}' })
    );
    await user.page.locator('#assistantInput').fill('这条消息应当因为会话失效而被拒绝');
    await user.page.locator('#assistantSend').click();
    await user.page.locator('#authModal:not([hidden])').waitFor({ timeout: 15000 });
    assert(
      (await user.page.locator('#authStatus').innerText()).includes('登录状态已过期'),
      '助手接口返回 401 后应提示重新登录'
    );
    assert(
      !(await user.page.evaluate(() => sessionStorage.getItem('zhida-token'))),
      '助手接口返回 401 后应清除失效 token'
    );
    await user.page.unroute('**/api/v1/support/assistant/stream');
    // 再次登录，供后面的管理端步骤使用。
    await user.page.locator('#username').fill('zhida_demo_user');
    await user.page.locator('#password').fill(password);
    await user.page.locator('button[value="login"]').click();
    await user.page.locator('#nav .nav-item').first().waitFor();

    const admin = await login(browser, 'zhida_demo_admin');
    opened.push(admin.context);
    for (const route of ['assignment', 'categories', 'catalog', 'knowledge']) {
      assert(await admin.page.locator(`[data-route="${route}"]`).isVisible(), `管理端缺少 ${route} 入口`);
    }
    await clickRoute(admin.page, 'knowledge');
    assert((await admin.page.locator('#knowledgeViewer').inputValue()) === 'product-support', '管理员知识库默认项不是公共库');
    assert(await admin.page.locator('#uploadForm').isVisible(), '管理员不能维护公共知识库');

    console.log('PASS 用户订单、结构化出处、长草稿确认、客服处理、用户退回/关闭评价、手动建单、主动造草稿、文档状态自动刷新、管理端公共知识库');
  } finally {
    for (const context of opened) await context.close().catch(() => {});
    await browser.close();
  }
})().catch(error => {
  console.error(`FAIL ${error.message}`);
  process.exitCode = 1;
});
