package com.emailmanager.backend.ai.service;

import com.emailmanager.backend.ai.dto.AiAnalysisResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI email analysis service powered by Gemini 2.0 Flash.
 *
 * <p>Provides email summarization, sentiment analysis, action item extraction,
 * and suggested reply generation.
 */
@Service
@Slf4j
public class AiService {

    private static final String MODEL = "gemini-2.0-flash";

    private final Client client;
    private final ObjectMapper objectMapper;

    public AiService(
            @Value("${ai.gemini.api-key:}") String apiKey,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        if (apiKey != null && !apiKey.isBlank()) {
            this.client = Client.builder().apiKey(apiKey).build();
            log.info("[AI] Gemini client initialized with API key");
        } else {
            this.client = null;
            log.warn("[AI] No Gemini API key configured — AI features disabled");
        }
    }

    /**
     * Analyze an email — returns summary, sentiment, action items, and a suggested reply.
     */
    public AiAnalysisResponse analyzeEmail(String subject, String from, String body) {
        if (client == null) {
            return new AiAnalysisResponse(
                    "AI not configured", "neutral", List.of(), "");
        }

        try {
            String prompt = buildAnalysisPrompt(subject, from, body);
            GenerateContentResponse response = client.models.generateContent(
                    MODEL, prompt, GenerateContentConfig.builder().build());
            String text = response.text();

            return parseAnalysisResponse(text);
        } catch (Exception e) {
            log.error("[AI] Analysis failed: {}", e.getMessage());
            return new AiAnalysisResponse(
                    "Analysis failed: " + e.getMessage(),
                    "unknown",
                    List.of(),
                    ""
            );
        }
    }

    /**
     * Generate a reply draft for an email.
     */
    public String generateReply(String subject, String from, String body, String tone) {
        if (client == null) return "AI not configured";

        try {
            String prompt = String.format("""
                    You are a professional email assistant. Draft a concise reply to this email.
                    
                    Tone: %s
                    
                    Original email:
                    From: %s
                    Subject: %s
                    Body:
                    %s
                    
                    Write ONLY the reply body text (no greeting header, no signature).
                    Keep it professional, concise, and under 150 words.
                    """, tone, from, subject, truncateBody(body));

            GenerateContentResponse response = client.models.generateContent(
                    MODEL, prompt, GenerateContentConfig.builder().build());
            return response.text();
        } catch (Exception e) {
            log.error("[AI] Reply generation failed: {}", e.getMessage());
            return "Failed to generate reply: " + e.getMessage();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildAnalysisPrompt(String subject, String from, String body) {
        return String.format("""
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
                """, from, subject, truncateBody(body));
    }

    private AiAnalysisResponse parseAnalysisResponse(String text) {
        try {
            // Clean potential markdown wrapping
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

            JsonNode node = objectMapper.readTree(cleaned);

            String summary = node.has("summary") ? node.get("summary").asText() : "No summary";
            String sentiment = node.has("sentiment") ? node.get("sentiment").asText() : "neutral";
            String suggestedReply = node.has("suggestedReply") ? node.get("suggestedReply").asText() : "";

            List<String> actionItems = new ArrayList<>();
            if (node.has("actionItems") && node.get("actionItems").isArray()) {
                for (JsonNode item : node.get("actionItems")) {
                    actionItems.add(item.asText());
                }
            }

            return new AiAnalysisResponse(summary, sentiment, actionItems, suggestedReply);
        } catch (Exception e) {
            log.warn("[AI] Failed to parse JSON response, using raw text: {}", e.getMessage());
            return new AiAnalysisResponse(text, "neutral", List.of(), "");
        }
    }

    private String truncateBody(String body) {
        if (body == null) return "(empty)";
        // Strip HTML tags for cleaner AI input
        String clean = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return clean.length() > 3000 ? clean.substring(0, 3000) + "…" : clean;
    }
}
