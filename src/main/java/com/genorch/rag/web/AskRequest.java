package com.genorch.rag.web;

/**
 * Body for {@code POST /ask}.
 *
 * @param question       the question, in Chinese or English
 * @param version        optional Spring AI version filter (e.g. {@code 2.0.1}); empty = all versions
 * @param conversationId optional opaque conversation id; when present, earlier turns are recalled
 *                       and this turn is remembered, enabling multi-turn Q&amp;A
 */
public record AskRequest(String question, String version, String conversationId) {
}
