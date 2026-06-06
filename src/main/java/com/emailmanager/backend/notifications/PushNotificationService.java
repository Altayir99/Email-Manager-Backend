package com.emailmanager.backend.notifications;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import java.io.File;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;

/**
 * Sends FCM push notifications to the mobile app when new emails arrive.
 * Requires firebase-service-account.json in src/main/resources/
 */
@Service
@Slf4j
public class PushNotificationService {

    @Value("${app.firebase.enabled:false}")
    private boolean firebaseEnabled;

    @PostConstruct
    public void initialize() {
        if (!firebaseEnabled) {
            log.info("[Push] Firebase disabled — set app.firebase.enabled=true and add service account JSON to enable");
            return;
        }
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                // Try mounted file path first (Docker prod), then classpath (local dev)
                Resource firebaseResource;
                File mountedFile = new File("/app/firebase-service-account.json");
                if (mountedFile.exists()) {
                    firebaseResource = new FileSystemResource(mountedFile);
                } else {
                    firebaseResource = new ClassPathResource("firebase-service-account.json");
                }
                GoogleCredentials credentials = GoogleCredentials
                        .fromStream(firebaseResource.getInputStream())
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("[Push] Firebase Admin SDK initialized");
            }
        } catch (IOException e) {
            log.error("[Push] Failed to initialize Firebase: {}", e.getMessage());
        }
    }

    /**
     * Send a "new email" push to a specific FCM device token.
     */
    public void sendNewEmailNotification(
            String fcmToken,
            String senderName,
            String subject,
            String snippet,
            String accountId) {

        if (!firebaseEnabled || fcmToken == null || fcmToken.isBlank()) return;

        try {
            Message message = Message.builder()
                    .setToken(fcmToken)
                    .setNotification(Notification.builder()
                            .setTitle(senderName.isBlank() ? "New Email" : senderName)
                            .setBody(subject.isBlank() ? "(no subject)" : subject)
                            .build())
                    .putData("accountId", accountId)
                    .putData("snippet", snippet != null ? snippet : "")
                    .putData("type", "new_email")
                    .build();

            String response = FirebaseMessaging.getInstance().send(message);
            log.info("[Push] Sent to {}: {}", fcmToken.substring(0, 10) + "...", response);

        } catch (Exception e) {
            log.error("[Push] Failed to send notification: {}", e.getMessage());
        }
    }
}
