package com.emailmanager.backend.ai.controller;

import com.emailmanager.backend.accounts.service.EmailAccountService;
import com.emailmanager.backend.ai.dto.AiAnalysisResponse;
import com.emailmanager.backend.ai.service.AiService;
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
 * <p>All endpoints require authentication and account ownership verification.
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/ai")
@RequiredArgsConstructor
@Slf4j
public class AiController {

    private final EmailAccountService accountService;
    private final AiService aiService;
    private final CachedEmailRepository cachedEmailRepo;

    /**
     * Analyze an email — returns summary, sentiment, action items, and suggested reply.
     */
    @GetMapping("/analyze/{uid}")
    public ResponseEntity<AiAnalysisResponse> analyzeEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder) {

        accountService.getAccountEntity(user.getUsername(), accountId); // auth check

        CachedEmail email = cachedEmailRepo
                .findByAccountIdAndFolderAndUid(accountId, folder, uid)
                .orElse(null);

        if (email == null) {
            return ResponseEntity.notFound().build();
        }

        String body = email.getBodyText() != null ? email.getBodyText() : email.getBodyHtml();
        AiAnalysisResponse analysis = aiService.analyzeEmail(
                email.getSubject(),
                email.getFromAddress(),
                body
        );

        return ResponseEntity.ok(analysis);
    }

    /**
     * Generate a reply draft for an email.
     */
    @PostMapping("/reply/{uid}")
    public ResponseEntity<Map<String, String>> generateReply(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable UUID accountId,
            @PathVariable long uid,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestParam(defaultValue = "professional") String tone) {

        accountService.getAccountEntity(user.getUsername(), accountId); // auth check

        CachedEmail email = cachedEmailRepo
                .findByAccountIdAndFolderAndUid(accountId, folder, uid)
                .orElse(null);

        if (email == null) {
            return ResponseEntity.notFound().build();
        }

        String body = email.getBodyText() != null ? email.getBodyText() : email.getBodyHtml();
        String reply = aiService.generateReply(
                email.getSubject(),
                email.getFromAddress(),
                body,
                tone
        );

        return ResponseEntity.ok(Map.of("reply", reply, "tone", tone));
    }
}
