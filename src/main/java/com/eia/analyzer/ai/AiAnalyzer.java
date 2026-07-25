package com.eia.analyzer.ai;

import com.eia.analyzer.model.Finding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pass 2 of the analyzer: LLM-assisted semantic review.
 *
 * Where the classic pass matches syntactic patterns, this pass asks a language
 * model to reason about what the code is *supposed* to do -- the kind of intent
 * mismatch no deterministic rule can express.
 *
 * The raw model reply is cached on disk so a demo can be replayed offline and
 * produce exactly the same report every time.
 */
public class AiAnalyzer {

    private static final Path CACHE_DIR = Path.of(".cache");

    private final ClaudeClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiAnalyzer() {
        this(null);
    }

    public AiAnalyzer(Double temperature) {
        this.client = new ClaudeClient(temperature);
    }

    /**
     * Normal mode: use the cache when offline, write it when online.
     */
    public List<Finding> analyze(String fileName, String sourceCode, boolean offline)
            throws Exception {

        Path cacheFile = CACHE_DIR.resolve(fileName + ".ai.json");
        String rawReply;

        if (offline) {
            if (!Files.exists(cacheFile)) {
                throw new IllegalStateException(
                        "Offline mode requested but no cache found at " + cacheFile
                                + ". Run once online first.");
            }
            rawReply = Files.readString(cacheFile);
            System.out.println("  (offline mode: replaying cached response)");
        } else {
            rawReply = client.complete(buildPrompt(fileName, sourceCode));
            Files.createDirectories(CACHE_DIR);
            Files.writeString(cacheFile, rawReply);
        }

        return parseFindings(rawReply);
    }

    /**
     * Experiment mode: always hit the API, never touch the cache. Used by the
     * reproducibility experiment, where caching would defeat the purpose.
     */
    public List<Finding> analyzeOnce(String fileName, String sourceCode) throws Exception {
        String rawReply = client.complete(buildPrompt(fileName, sourceCode));
        return parseFindings(rawReply);
    }

    public Double getTemperature() {
        return client.getTemperature();
    }

    private String buildPrompt(String fileName, String sourceCode) {
        StringBuilder numbered = new StringBuilder();
        String[] lines = sourceCode.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            numbered.append(i + 1).append(": ").append(lines[i]).append("\n");
        }

        return """
                You are a static analysis engine for Java source code.

                Analyze the file below and report semantic problems: logic bugs,
                off-by-one errors, methods whose behaviour contradicts their name,
                misleading identifiers, and unnecessary complexity.

                Do NOT report purely syntactic issues such as unused variables,
                unreachable code, empty catch blocks, or String comparison with ==.
                A separate deterministic pass already covers those.

                Respond with a JSON array and nothing else. No prose, no markdown
                fences. Each element must have exactly these keys:
                  "line"       : integer, the line number in the file
                  "severity"   : one of "INFO", "WARNING", "ERROR"
                  "type"       : short PascalCase label, e.g. "OffByOneError"
                  "message"    : one sentence explaining the problem
                  "suggestion" : one sentence explaining how to fix it

                If you find nothing, respond with an empty array: []

                File: %s
                ---
                %s
                ---
                """.formatted(fileName, numbered);
    }

    private List<Finding> parseFindings(String rawReply) {
        List<Finding> findings = new ArrayList<>();
        String cleaned = stripCodeFences(rawReply).trim();

        try {
            JsonNode array = mapper.readTree(cleaned);
            if (!array.isArray()) {
                System.out.println("  (warning: model did not return a JSON array)");
                return findings;
            }

            for (JsonNode node : array) {
                findings.add(new Finding(
                        node.path("line").asInt(-1),
                        parseSeverity(node.path("severity").asText("WARNING")),
                        node.path("type").asText("AiFinding"),
                        node.path("message").asText(""),
                        node.path("suggestion").asText(""),
                        Finding.Source.AI
                ));
            }
        } catch (Exception e) {
            System.out.println("  (warning: could not parse the model reply as JSON: "
                    + e.getMessage() + ")");
        }

        findings.sort(Comparator.comparingInt(Finding::getLine));
        return findings;
    }

    /**
     * Models sometimes wrap JSON in markdown fences despite instructions.
     * Being defensive here is cheaper than a failed demo.
     */
    private String stripCodeFences(String text) {
        String result = text.trim();
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            if (firstNewline > -1) {
                result = result.substring(firstNewline + 1);
            }
            int closingFence = result.lastIndexOf("```");
            if (closingFence > -1) {
                result = result.substring(0, closingFence);
            }
        }
        return result;
    }

    private Finding.Severity parseSeverity(String value) {
        try {
            return Finding.Severity.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Finding.Severity.WARNING;
        }
    }
}