package com.genorch.rag.web;

/** Question for {@code POST /ask}; {@code version} optionally filters the corpus to one Spring AI version. */
public record AskRequest(String question, String version) {
}
