/**
 * 会话持久化包：把聊天会话 / 消息 / 文件夹存进本地 SQLite。
 *
 * <p>{@code ConversationStore} 用 JDBC 直连 SQLite（{@code data/conversations.db}）维护
 * {@code conversation} / {@code message} / {@code folder} 三张表；{@code RagService} 生成前
 * 从它读最近消息作多轮上下文、完成后原子写回 user/assistant 两条消息；{@code ConversationController}
 * 暴露 {@code /api/*} 供前端聊天 UI 做会话/文件夹 CRUD。会话数据与向量库（pgvector RDS）分离，
 * 是嵌入式单文件、零运维、重启不丢。
 */
package com.genorch.rag.conversation;
