package com.zhida.agent.application;

/**
 * 一次 Agent 会话的调用模式。模式由服务端入口决定，客户端只能选择调用哪个入口，
 * 不能在请求体中声明模式，否则调用者可以自行选择更宽松的系统指令和工具集。
 *
 * <ul>
 *   <li>{@link #RESEARCH}：原研究助手，使用通用指令与包含联网搜索的工具集。
 *   <li>{@link #SUPPORT}：售后助手，使用售后指令与只读查询 + 草稿工具集。
 * </ul>
 */
public enum AgentMode {
  RESEARCH,
  SUPPORT
}
