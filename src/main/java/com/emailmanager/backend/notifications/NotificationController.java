package com.emailmanager.backend.notifications;

import com.emailmanager.backend.user.User;
import com.emailmanager.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final UserRepository userRepository;

    /**
     * Called by the Flutter app on startup to register/update the FCM token.
     * POST /api/notifications/register
     * Body: { "fcmToken": "..." }
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> registerToken(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body) {

        String token = body.get("fcmToken");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "fcmToken is required"));
        }

        userRepository.findByUsername(userDetails.getUsername()).ifPresent(user -> {
            user.setFcmToken(token);
            userRepository.save(user);
            log.info("[Push] Token registered for user: {}", userDetails.getUsername());
        });

        return ResponseEntity.ok(Map.of("status", "registered"));
    }

    /**
     * Remove FCM token on logout.
     * DELETE /api/notifications/register
     */
    @DeleteMapping("/register")
    public ResponseEntity<Void> unregisterToken(
            @AuthenticationPrincipal UserDetails userDetails) {

        userRepository.findByUsername(userDetails.getUsername()).ifPresent(user -> {
            user.setFcmToken(null);
            userRepository.save(user);
        });
        return ResponseEntity.noContent().build();
    }
}
