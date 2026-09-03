package com.genorch.rag.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.stereotype.Component;

import com.genorch.rag.config.RagProperties;
import com.genorch.rag.document.DocumentMeta;

/**
 * Local-corpus {@link DocumentReader}: reads the Spring AI reference source (AsciiDoc) that
 * was checked out under {@code data/raw/spring-ai/{version}}, instead of fetching HTML from
 * the network.
 *
 * <p>The directory layout <em>is</em> the version management: each child directory of
 * {@code data/raw/spring-ai/} is one Spring AI version, so adding a version is just adding
 * a directory (cloned from a git tag). Every emitted {@link Document} carries the
 * {@link DocumentMeta#VERSION} tag taken from the directory name, so retrieval can filter
 * by version.
 *
 * <p>The AsciiDoc source (see {@code spring-ai-docs/src/main/antora/modules/ROOT/pages}) is
 * the single source of truth behind docs.spring.io, is versioned by git tag, and is Apache
 * 2.0 licensed — so a local snapshot of it is both reproducible and re-distributable.
 *
 * <p>This is the only reader: the corpus is the versioned local AsciiDoc snapshot.
 */
@Component
public class AsciiDocDocumentReader implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(AsciiDocDocumentReader.class);

    private static final String BASE_URL = "https://docs.spring.io/spring-ai/reference";
    private static final String PAGES_SUBDIR = "spring-ai-docs/src/main/antora/modules/ROOT/pages";

    private final Path corpusRoot;

    public AsciiDocDocumentReader(RagProperties properties) {
        this.corpusRoot = Path.of(properties.dataDir()).resolve("raw").resolve("spring-ai");
    }

    @Override
    public List<Document> get() {
        if (!Files.isDirectory(corpusRoot)) {
            log.warn("AsciiDoc corpus root not found: {} (clone the docs first)", corpusRoot);
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> versionDirs = Files.list(corpusRoot)) {
            versionDirs.filter(Files::isDirectory)
                .sorted()
                .forEach(versionDir -> documents.addAll(readVersion(versionDir)));
        }
        catch (IOException e) {
            log.warn("failed to list corpus root {}: {}", corpusRoot, e.getMessage());
        }
        log.info("AsciiDocDocumentReader: {} pages across versions in {}", documents.size(), corpusRoot);
        return documents;
    }

    private List<Document> readVersion(Path versionDir) {
        String version = versionDir.getFileName().toString();
        Path pagesDir = versionDir.resolve(PAGES_SUBDIR);
        if (!Files.isDirectory(pagesDir)) {
            log.warn("AsciiDoc pages directory not found for version {}: {}", version, pagesDir);
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(pagesDir)) {
            paths.filter(path -> path.toString().endsWith(".adoc"))
                .forEach(path -> documents.add(read(path, version, pagesDir)));
        }
        catch (IOException e) {
            log.warn("failed to walk AsciiDoc pages {}: {}", pagesDir, e.getMessage());
        }
        log.info("AsciiDocDocumentReader: {} pages (version {})", documents.size(), version);
        return documents;
    }

    private Document read(Path file, String version, Path pagesDir) {
        String raw = readText(file);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(DocumentMeta.SOURCE_URL, toUrl(file, pagesDir));
        metadata.put(DocumentMeta.TITLE, extractTitle(raw));
        metadata.put(DocumentMeta.VERSION, version);
        return new Document(cleanAsciiDoc(raw), metadata);
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            log.warn("failed to read {}: {}", file, e.getMessage());
            return "";
        }
    }

    /** Maps a {@code pages}-relative path to the rendered docs.spring.io URL. */
    private String toUrl(Path file, Path pagesDir) {
        String rel = pagesDir.relativize(file).toString().replace('\\', '/');
        if (rel.endsWith(".adoc")) {
            rel = rel.substring(0, rel.length() - ".adoc".length()) + ".html";
        }
        return BASE_URL + "/" + rel;
    }

    /**
     * Light AsciiDoc cleanup: drops directives/comments/attributes and code fences, keeps the
     * prose and code, and strips heading markers plus the most common inline markup. This is
     * not a full AsciiDoc renderer — just enough to embed the reference docs as plain text.
     */
    static String cleanAsciiDoc(String adoc) {
        StringBuilder sb = new StringBuilder(adoc.length());
        for (String line : adoc.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()
                    || t.startsWith("//")
                    || t.startsWith("include::")
                    || t.startsWith("ifdef::")
                    || t.startsWith("endif::")) {
                continue;
            }
            // document attribute header, e.g. ":icons: font" or ":icons:"
            if (t.startsWith(":") && t.indexOf(':', 1) > 0) {
                continue;
            }
            // code fence / block attribute / anchor
            if (t.equals("----") || t.startsWith("[source") || t.startsWith("[cols")
                    || t.startsWith("[.") || t.startsWith("[#") || t.startsWith("[[")) {
                continue;
            }
            String s = t.replaceFirst("^=+\\s*", "");
            // inline markup: bold/italic/emphasis, inline code, and xref/link wrappers
            s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("(?<!\\*)\\*([^*\\s]+)\\*(?!\\*)", "$1")
                .replace("``", "").replace("`", "")
                .replaceAll("__([^_]+)__", "$1")
                .replaceAll("(?:xref|link):[^\\[]+\\[([^\\]]+)\\]", "$1");
            if (s.isBlank()) {
                continue;
            }
            sb.append(s).append('\n');
        }
        return sb.toString().trim();
    }

    /** The document title is the single {@code =} heading; fall back to the first {@code ==}. */
    static String extractTitle(String adoc) {
        for (String line : adoc.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("=") && !t.startsWith("==")) {
                return t.replaceFirst("^=+\\s*", "").trim();
            }
        }
        for (String line : adoc.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("==")) {
                return t.replaceFirst("^=+\\s*", "").trim();
            }
        }
        return "";
    }
}
