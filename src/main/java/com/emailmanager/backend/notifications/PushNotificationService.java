package com.emailmanager.backend.notifications;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApsAlert;
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
     *
     * iOS note: ApnsConfig with explicit Aps.alert is REQUIRED for iOS to show
     * a visible notification banner. Without it FCM delivers a silent background
     * message only, which never appears on screen.
     */
    public void sendNewEmailNotification(
            String fcmToken,
            String senderName,
            String subject,
            String snippet,
            String accountId,
            String folder,
            long uid) {

        if (!firebaseEnabled || fcmToken == null || fcmToken.isBlank()) return;

        String title = senderName == null || senderName.isBlank() ? "New Email" : senderName;
        String body  = subject   == null || subject.isBlank()   ? "(no subject)" : subject;

        try {
            Message message = Message.builder()
                    .setToken(fcmToken)
                    // ── Shared notification (Android foreground, FCM console) ──
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    // ── iOS / APNS: REQUIRED for banner to appear on screen ──
                    // Without ApnsConfig, FCM treats the message as data-only
                    // and iOS delivers it silently (no banner, no sound).
                    .setApnsConfig(ApnsConfig.builder()
                            .putHeader("apns-push-type", "alert")
                            .putHeader("apns-priority", "10")
                            .setAps(Aps.builder()
                                    .setAlert(ApsAlert.builder()
                                            .setTitle(title)
                                            .setBody(body)
                                            .build())
                                    .setSound("default")
                                    .setBadge(1)
                                    .build())
                            .build())
                    // ── Android: high priority wakes device from doze ──
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build())
                    // ── Data payload (accountId + folder + uid for tap deep-link) ──
                    .putData("accountId", accountId)
                    .putData("folder", folder != null ? folder : "INBOX")
                    .putData("uid", String.valueOf(uid))
                    .putData("snippet", snippet != null ? snippet : "")
                    .putData("type", "new_email")
                    .build();

            String response = FirebaseMessaging.getInstance().send(message);
            log.info("[Push] Sent to {}...: {}", fcmToken.substring(0, Math.min(10, fcmToken.length())), response);

        } catch (Exception e) {
            log.error("[Push] Failed to send notification: {}", e.getMessage());
        }
    }
}
