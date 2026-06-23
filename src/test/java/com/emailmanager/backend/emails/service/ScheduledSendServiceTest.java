package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.emails.dto.PendingSendResponse;
import com.emailmanager.backend.emails.dto.SendEmailRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledSendServiceTest {

    private EmailSendService sendService;
    private ScheduledSendService scheduledSendService;
    private EmailAccount testAccount;

    @BeforeEach
    void setUp() {
        sendService = mock(EmailSendService.class);
        scheduledSendService = new ScheduledSendService(sendService);

        testAccount = new EmailAccount();
        testAccount.setId(UUID.randomUUID());
        testAccount.setEmailAddress("test@example.com");
    }

    @Nested
    @DisplayName("Queue Send")
    class QueueSend {
        @Test
        @DisplayName("queueSend returns a valid PendingSendResponse with 'queued' status")
        void queueSendReturnsQueuedResponse() {
            SendEmailRequest request = new SendEmailRequest(
                    "to@example.com", null, null, "Test Subject", null, "Body text");

            PendingSendResponse response = scheduledSendService.queueSend(testAccount, request, null);

            assertNotNull(response);
            assertEquals("queued", response.status());
            assertNotNull(response.sendId());
            assertNotNull(response.expiresAt());
        }

        @Test
        @DisplayName("queueSend makes the sendId pending")
        void queueSendMakesPending() {
            SendEmailRequest request = new SendEmailRequest(
                    "to@example.com", null, null, "Test", null, "Body");

            PendingSendResponse response = scheduledSendService.queueSend(testAccount, request, null);

            assertTrue(scheduledSendService.isPending(response.sendId()));
        }
    }

    @Nested
    @DisplayName("Cancel Send")
    class CancelSend {
        @Test
        @DisplayName("cancelSend before delivery returns 'cancelled'")
        void cancelBeforeDelivery() {
            SendEmailRequest request = new SendEmailRequest(
                    "to@example.com", null, null, "Test", null, "Body");

            PendingSendResponse queued = scheduledSendService.queueSend(testAccount, request, null);
            PendingSendResponse cancelled = scheduledSendService.cancelSend(queued.sendId());

            assertEquals("cancelled", cancelled.status());
            assertFalse(scheduledSendService.isPending(queued.sendId()));
            // Verify sendEmail was never called
            verify(sendService, never()).sendEmail(any(), any(), any());
        }

        @Test
        @DisplayName("cancelSend for unknown sendId returns 'sent'")
        void cancelUnknownReturnsSent() {
            UUID unknownId = UUID.randomUUID();
            PendingSendResponse response = scheduledSendService.cancelSend(unknownId);

            assertEquals("sent", response.status());
        }
    }

    @Nested
    @DisplayName("Delivery")
    class Delivery {
        @Test
        @DisplayName("email is delivered after delay expires")
        void deliveryAfterDelay() throws InterruptedException {
            SendEmailRequest request = new SendEmailRequest(
                    "to@example.com", List.of("cc@example.com"), null,
                    "Test Subject", "<p>HTML</p>", "Body text");

            PendingSendResponse queued = scheduledSendService.queueSend(testAccount, request, null);

            // Wait for the scheduled delivery (10s default + buffer)
            Thread.sleep(12_000);

            // Verify the email was sent
            verify(sendService, times(1)).sendEmail(eq(testAccount), eq(request), isNull());
            assertFalse(scheduledSendService.isPending(queued.sendId()));
        }
    }
}
