/**
 * 装配层：把 Spring AI 2.0 的抽象接口拼成具体 Bean，并绑定配置。
 *
 * <p>职责与依赖方向：
 * <ul>
 *   <li>{@code RagConfig} —— 唯一的 {@code @Configuration}，产出检索/重排/切分/向量库/聊天客户端等 Bean；</li>
 *   <li>{@code RagProperties} —— 绑定 {@code application.yml} 的 {@code app.rag.*} 配置；</li>
 *   <li>{@code ApiKeyStartupCheck} —— 启动时提示缺失的 DashScope key。</li>
 * </ul>
 *
 * <p>本包是「组装车间」：它依赖 {@code document / retrieve / rerank / observability} 的具体类，
 * 但不含任何业务逻辑。
 */
package com.genorch.rag.config;
