package com.eia.analyzer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal HTTP client for the Anthropic Messages API.
 *
 * The API key is read from the ANTHROPIC_API_KEY environment variable and is
 * never stored in the repository.
 *
 * The temperature parameter controls how much randomness the model uses when
 * picking each token. At temperature 0 the model always takes the most likely
 * continuation, which makes the output far more stable -- but, as our
 * experiment shows, not perfectly stable.
 */
public class ClaudeClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String DEFAULT_MODEL = "claude-sonnet-5";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final Double temperature;

    public ClaudeClient() {
        this(null);
    }

    public ClaudeClient(Double temperature) {
        this.temperature = temperature;
        this.apiKey = System.getenv("ANTHROPIC_API_KEY");

        String configuredModel = System.getenv("ANTHROPIC_MODEL");
        this.model = (configuredModel == null || configuredModel.isBlank())
                ? DEFAULT_MODEL
                : configuredModel;

        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getModel() {
        return model;
    }

    public Double getTemperature() {
        return temperature;
    }

    /**
     * Sends a single-turn prompt and returns the concatenated text of the reply.
     */
    public String complete(String prompt) throws Exception {
        if (!hasApiKey()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set. Export it, or run with --offline.");
        }

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", 2000);

        // Only sent when the caller explicitly asked for it. Recent Claude
        // models reject any non-default value with HTTP 400: the sampling
        // knobs were deprecated in favour of prompt-level control.
        if (temperature != null) {
            requestBody.put("temperature", temperature);
        }

        ArrayNode messages = requestBody.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(120))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            if (response.body().contains("temperature")
                    && response.body().contains("deprecated")) {
                throw new RuntimeException(
                        "This model no longer accepts a custom temperature: the sampling "
                                + "parameters were deprecated. Run without --temperature, or set "
                                + "ANTHROPIC_MODEL to an older model that still supports it.");
            }
            throw new RuntimeException("Claude API returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode contentBlocks = root.path("content");

        StringBuilder text = new StringBuilder();
        for (JsonNode block : contentBlocks) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }
}