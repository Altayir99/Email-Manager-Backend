package com.emailmanager.backend.ai.dto;

import java.util.List;

/**
 * Response from the AI analysis endpoint.
 */
public record AiAnalysisResponse(
        String summary,
        String sentiment,
        List<String> actionItems,
        String suggestedReply
) {}
