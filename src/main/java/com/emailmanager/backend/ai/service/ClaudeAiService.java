package com.emailmanager.backend.ai.service;

import com.emailmanager.backend.ai.dto.AiAnalysisResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI email analysis service powered by Anthropic Claude.
 *
 * <p>Uses the Anthropic Messages API directly via Spring RestClient — no SDK needed.
 * Falls back to Gemini (AiService) when no API key is configured.
 */
@Service
@Slf4j
public class ClaudeAiService {

    private static final String ANTHROPIC_URL   = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VER   = "2023-06-01";

    private final String       apiKey;
    private final String       model;
    private final ObjectMapper objectMapper;
    private final RestClient   restClient;

    public ClaudeAiService(
            @Value("${ai.claude.api-key:}") String apiKey,
            @Value("${ai.claude.model:claude-opus-4-5}") String model,
            ObjectMapper objectMapper) {
        this.apiKey       = apiKey;
        this.model        = model;
        this.objectMapper = objectMapper;
        this.restClient   = RestClient.builder().build();

        if (isEnabled()) {
            log.info("[AI] Claude initialized — model: {}", model);
        } else {
            log.info("[AI] Claude not configured (no ANTHROPIC_API_KEY)");
        }
    }

    /** True when an Anthropic API key is set and non-empty. */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public AiAnalysisResponse analyzeEmail(String subject, String from, String body) {
        if (!isEnabled()) {
            return new AiAnalysisResponse("Claude not configured", "neutral", List.of(), "");
        }
        try {
            String text = callClaude(buildAnalysisPrompt(subject, from, body));
            return parseAnalysisResponse(text);
        } catch (Exception e) {
            log.error("[AI] Claude analysis failed: {}", e.getMessage());
            return new AiAnalysisResponse("Analysis failed: " + e.getMessage(), "unknown", List.of(), "");
        }
    }

    public String generateReply(String subject, String from, String body, String tone) {
        if (!isEnabled()) return "Claude not configured";
        try {
            String prompt = """
                    You are a professional email assistant. Draft a concise reply to this email.
                    
                    Tone: %s
                    
                    Original email:
                    From: %s
                    Subject: %s
                    Body:
                    %s
                    
                    Write ONLY the reply body text (no greeting header, no signature).
                    Keep it professional, concise, and under 150 words.
                    """.formatted(tone, from, subject, truncateBody(body));
            return callClaude(prompt);
        } catch (Exception e) {
            log.error("[AI] Claude reply generation failed: {}", e.getMessage());
            return "Failed to generate reply: " + e.getMessage();
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /** Calls the Anthropic Messages API and returns the assistant text. */
    private String callClaude(String userMessage) throws Exception {
        Map<String, Object> requestBody = Map.of(
                "model",      model,
                "max_tokens", 1024,
                "messages",   List.of(Map.of("role", "user", "content", userMessage))
        );

        String rawResponse = restClient.post()
                .uri(ANTHROPIC_URL)
                .header("x-api-key",         apiKey)
                .header("anthropic-version", ANTHROPIC_VER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        // Parse: {"content": [{"type": "text", "text": "..."}], ...}
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode content = root.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new RuntimeException("Unexpected Claude response: " + rawResponse);
        }
        return content.get(0).get("text").asText();
    }

    private String buildAnalysisPrompt(String subject, String from, String body) {
        return """
                Analyze this email and respond in STRICT JSON format with NO markdown:
                
                {
                  "summary": "2-3 sentence summary of the email",
                  "sentiment": "positive | negative | neutral | urgent",
                  "actionItems": ["action 1", "action 2"],
                  "suggestedReply": "A brief 1-2 sentence suggested reply"
                }
                
                Email:
                From: %s
                Subject: %s
                Body:
                %s
                
                Respond ONLY with valid JSON. No explanation, no markdown code blocks.
                """.formatted(from, subject, truncateBody(body));
    }

    private AiAnalysisResponse parseAnalysisResponse(String text) {
        try {
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

            JsonNode node = objectMapper.readTree(cleaned);

            String summary        = node.has("summary")        ? node.get("summary").asText()        : "No summary";
            String sentiment      = node.has("sentiment")      ? node.get("sentiment").asText()      : "neutral";
            String suggestedReply = node.has("suggestedReply") ? node.get("suggestedReply").asText() : "";

            List<String> actionItems = new ArrayList<>();
            if (node.has("actionItems") && node.get("actionItems").isArray()) {
                for (JsonNode item : node.get("actionItems")) {
                    actionItems.add(item.asText());
                }
            }

            return new AiAnalysisResponse(summary, sentiment, actionItems, suggestedReply);
        } catch (Exception e) {
            log.warn("[AI] Failed to parse Claude JSON response, using raw text: {}", e.getMessage());
            return new AiAnalysisResponse(text, "neutral", List.of(), "");
        }
    }

    private String truncateBody(String body) {
        if (body == null) return "(empty)";
        String clean = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return clean.length() > 3000 ? clean.substring(0, 3000) + "…" : clean;
    }
}
