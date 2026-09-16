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
      `操作按钮 ${name} 不可用；面板=[${panel.replace(/\n+/g, ' | ')}] 状态=[${tags.join(',')}]；原因=${error.message.split('\n')[0]}`
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
 * 打开工单详情并等待详情真正渲染完成。
 *
 * <p>这里必须用 reload 而不是 goto(ticketUrl)：当目标 URL 与当前地址只差 hash 时，
 * goto 属于同文档导航，浏览器不会重新加载文档，SPA 不会重新初始化，页面会停留在上一次
 * 渲染的结果（可能已经是过期状态）。reload 保证拿到服务端最新状态。
 */
async function openTicket(page, ticketUrl, ticketId) {
  const sameDocument = page.url().split('#')[0] === ticketUrl.split('#')[0];
  if (sameDocument) {
    await page.reload({ waitUntil: 'networkidle' });
  } else {
    await page.goto(ticketUrl, { waitUntil: 'networkidle' });
  }
  await page.getByText(`工单 ${ticketId.slice(0, 8)}`, { exact: false }).first().waitFor({ timeout: 15000 });
}

/**
 * 稳定的详情操作入口：先把后端状态确认为期望值，再打开详情并执行操作。
 *
 * <p>偶发的页面渲染未完成（例如会话恢复较慢）会让单次等待超时，但后端状态其实已经正确。
 * 这里把「后端状态」作为唯一事实来源，按钮找不到时重载重试若干次，避免把渲染抖动
 * 误报成功能缺陷；重试后仍失败才输出面板与状态标签，便于定位真实问题。
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
      if (afterOpen) await afterOpen(page);
      if (fill) await page.locator(fill.selector).fill(fill.value);
      await waitForAction(page, action, 8000);
      await page.locator(`[data-act="${action}"]`).click();
      return;
    } catch (error) {
      lastError = error;
      // 重试前再确认后端状态没有被并发改变，否则重试只会掩盖真实冲突。
      await expectTicketStatus(page, ticketId, expectedStatus);
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
      const body = [
        sseEvent('answer.started', {}, 1),
        sseEvent('tool.completed', { toolName: 'knowledge_search', resultPreview: '{截断审计摘要}' }, 2),
        sseEvent('support.knowledge.evidence', {
          evidenceSufficient: true,
          message: '命中 1 个片段',
          nextAction: null,
          sources: [{ filename: 'fictional-product-support.md', pageNumber: null, chunkIndex: 1, excerpt: '已付款但未开通时，请提交售后工单并关联订单。' }]
        }, 3),
        sseEvent('answer.delta', { content: '订单状态为已付款、未开通。依据如下，建议创建工单。' }, 4),
        sseEvent('tool.completed', { toolName: 'support_ticket_draft', resultPreview: '{超过 800 字后会截断}' }, 5),
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
    await user.page.getByText('待受理', { exact: true }).first().waitFor();

    const agent = await login(browser, 'zhida_demo_agent');
    opened.push(agent.context);
    assert(await agent.page.locator('[data-route="pending"]').isVisible(), '客服端缺少待受理队列');
    assert(!(await agent.page.locator('[data-route="assistant"]').count()), '客服端不应展示用户售后助手');
    await clickRoute(agent.page, 'pending');
    // 用新创建的标题精确定位：待受理队列可能同时存在多个演示工单。
    await agent.page.locator('.ticket-row').filter({ hasText: '订单已付款但服务未开通' }).first().click();

    // 每次操作前显式等待按钮可交互，避免页面异步刷新期间点到已被替换的节点。
    const actionButton = name => agent.page.locator(`[data-act="${name}"]`);
    const clickAction = async name => {
      const button = actionButton(name);
      await button.waitFor({ state: 'visible' });
      await button.click();
    };

    await clickAction('claim');
    await agent.page.locator('#replyText').waitFor({ state: 'visible' });
    await agent.page.locator('#replyText').fill('正在核对虚构订单与订阅开通记录。');
    await clickAction('reply');

    await agent.page.locator('#solutionText').waitFor({ state: 'visible' });
    await agent.page.locator('#solutionText').fill('虚构方案：重新同步订阅状态后，请退出并重新登录。');
    await clickAction('solution');
    // 以后端状态为准判断转换是否完成：页面文字既可能来自历史事件，也可能尚未刷新。
    await expectTicketStatus(agent.page, ticketId, 'AWAITING_CONFIRMATION');

    await actOnTicket(user.page, ticketUrl, ticketId, 'AWAITING_CONFIRMATION', 'reopen', {
      fill: { selector: '#commentText', value: '按方案操作后仍未开通，请继续处理。' }
    });
    await expectTicketStatus(user.page, ticketId, 'PROCESSING');

    await agent.page.reload({ waitUntil: 'networkidle' });
    await agent.page.locator('#solutionText').waitFor({ state: 'visible' });
    await agent.page.locator('#solutionText').fill('虚构方案：已完成第二次同步，请再次登录核对。');
    await clickAction('solution');
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

    const admin = await login(browser, 'zhida_demo_admin');
    opened.push(admin.context);
    for (const route of ['assignment', 'categories', 'catalog', 'knowledge']) {
      assert(await admin.page.locator(`[data-route="${route}"]`).isVisible(), `管理端缺少 ${route} 入口`);
    }
    await clickRoute(admin.page, 'knowledge');
    assert((await admin.page.locator('#knowledgeViewer').inputValue()) === 'product-support', '管理员知识库默认项不是公共库');
    assert(await admin.page.locator('#uploadForm').isVisible(), '管理员不能维护公共知识库');

    console.log('PASS 用户订单、结构化出处、长草稿确认、客服处理、用户退回/关闭评价、手动建单、管理端公共知识库');
  } finally {
    for (const context of opened) await context.close().catch(() => {});
    await browser.close();
  }
})().catch(error => {
  console.error(`FAIL ${error.message}`);
  process.exitCode = 1;
});
