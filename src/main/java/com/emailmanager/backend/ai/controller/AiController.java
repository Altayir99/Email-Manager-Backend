package com.emailmanager.backend.ai.controller;

import com.emailmanager.backend.accounts.service.EmailAccountService;
import com.emailmanager.backend.ai.dto.AiAnalysisResponse;
import com.emailmanager.backend.ai.service.AiService;
import com.emailmanager.backend.ai.service.ClaudeAiService;
import com.emailmanager.backend.cache.entity.CachedEmail;
import com.emailmanager.backend.cache.repository.CachedEmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for AI email analysis.
 *
 * <p>Prefers Claude (Anthropic) when ANTHROPIC_API_KEY is configured,
 * falls back to Gemini 2.0 Flash otherwise. Both models expose the same
 * response contract so no Flutter changes are required.
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/ai")
@RequiredArgsConstructor
@Slf4j
public class AiController {

    private final EmailAccountService   accountService;
    private final ClaudeAiService       claudeAiService;   // preferred
    private final AiService             aiService;          // Gemini fallback
    private final CachedEmailRepository cachedEmailRepo;

    /**
     * Analyze an email — returns summary, sentiment, action items, and suggested reply.
     * Uses Claude if available, Gemini otherwise.
     */
    @GetMapping("/analyze/{uid}")
    public ResponseEntity<AiAnalysisResponse> analyzeEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder) {

        accountService.getAccountEntity(user.getUsername(), accountId); // ownership check

        CachedEmail email = cachedEmailRepo
                .findByAccountIdAndFolderAndUid(accountId, folder, uid)
                .orElse(null);

        if (email == null) return ResponseEntity.notFound().build();

        String body = email.getBodyText() != null ? email.getBodyText() : email.getBodyHtml();

        AiAnalysisResponse analysis;
        if (claudeAiService.isEnabled()) {
            log.debug("[AI] Using Claude for analysis — uid={}", uid);
            analysis = claudeAiService.analyzeEmail(email.getSubject(), email.getFromAddress(), body);
        } else {
            log.debug("[AI] Claude not configured — using Gemini fallback for uid={}", uid);
            analysis = aiService.analyzeEmail(email.getSubject(), email.getFromAddress(), body);
        }

        return ResponseEntity.ok(analysis);
    }

    /**
     * Generate a reply draft for an email.
     * Uses Claude if available, Gemini otherwise.
     */
    @PostMapping("/reply/{uid}")
    public ResponseEntity<Map<String, String>> generateReply(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestParam(defaultValue = "professional") String tone) {

        accountService.getAccountEntity(user.getUsername(), accountId); // ownership check

        CachedEmail email = cachedEmailRepo
                .findByAccountIdAndFolderAndUid(accountId, folder, uid)
                .orElse(null);

        if (email == null) return ResponseEntity.notFound().build();

        String body = email.getBodyText() != null ? email.getBodyText() : email.getBodyHtml();

        String reply;
        String provider;
        if (claudeAiService.isEnabled()) {
            reply    = claudeAiService.generateReply(email.getSubject(), email.getFromAddress(), body, tone);
            provider = "claude";
        } else {
            reply    = aiService.generateReply(email.getSubject(), email.getFromAddress(), body, tone);
            provider = "gemini";
        }

        return ResponseEntity.ok(Map.of("reply", reply, "tone", tone, "provider", provider));
    }

    /**
     * Returns which AI provider is active.
     */
    @GetMapping("/provider")
    public ResponseEntity<Map<String, String>> getProvider(
            @AuthenticationPrincipal UserDetails user) {
        String provider = claudeAiService.isEnabled() ? "claude" : "gemini";
        return ResponseEntity.ok(Map.of("provider", provider));
    }
}
