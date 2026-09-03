/**
 * 元数据契约包：定义全链路共享的 Document 元数据 key。
 *
 * <p>{@code DocumentMeta} 是写路径（摄取时写入）与读路径（引用、评估时读取）之间的唯一契约，
 * 避免两边各写各的字符串字面量。新增元数据字段时只改这里。
 */
package com.genorch.rag.document;
