package com.emailmanager.backend.emails.service;

import com.emailmanager.backend.accounts.entity.EmailAccount;
import com.emailmanager.backend.accounts.repository.EmailAccountRepository;
import com.emailmanager.backend.emails.dto.PendingSendResponse;
import com.emailmanager.backend.emails.dto.SendEmailRequest;
import com.emailmanager.backend.emails.entity.PendingSend;
import com.emailmanager.backend.emails.repository.PendingSendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScheduledSendServiceTest {

    private EmailSendService sendService;
    private PendingSendRepository pendingSendRepository;
    private EmailAccountRepository accountRepository;
    private ScheduledSendService scheduledSendService;
    private EmailAccount testAccount;

    @BeforeEach
    void setUp() {
        sendService = mock(EmailSendService.class);
        pendingSendRepository = mock(PendingSendRepository.class);
        accountRepository = mock(EmailAccountRepository.class);
        scheduledSendService = new ScheduledSendService(
                sendService, pendingSendRepository, accountRepository);

        testAccount = new EmailAccount();
        testAccount.setId(UUID.randomUUID());
        testAccount.setEmailAddress("test@example.com");
    }

    @Nested
    @DisplayName("Queue Send")
    class QueueSend {
        @Test
        @DisplayName("queueSend returns a valid PendingSendResponse and persists a PENDING row")
        void queueSendReturnsQueuedResponse() {
            SendEmailRequest request = new SendEmailRequest(
                    "to@example.com", null, null, "Test Subject", null, "Body text");

            PendingSendResponse response = scheduledSendService.queueSend(testAccount, request, null);

            assertNotNull(response);
            assertEquals("queued", response.status());
            assertNotNull(response.sendId());
            assertNotNull(response.expiresAt());
            // A PENDING row is persisted with the same id.
            var captor = org.mockito.ArgumentCaptor.forClass(PendingSend.class);
            verify(pendingSendRepository).save(captor.capture());
            assertEquals(response.sendId(), captor.getValue().getId());
            assertEquals(PendingSend.PENDING, captor.getValue().getStatus());
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
            when(pendingSendRepository.markCancelledIfPending(queued.sendId())).thenReturn(1);

            PendingSendResponse cancelled = scheduledSendService.cancelSend(queued.sendId());

            assertEquals("cancelled", cancelled.status());
            assertFalse(scheduledSendService.isPending(queued.sendId()));
            verify(sendService, never()).sendEmail(any(), any(), any());
        }

        @Test
        @DisplayName("cancelSend for unknown sendId returns 'sent'")
        void cancelUnknownReturnsSent() {
            UUID unknownId = UUID.randomUUID();
            // markCancelledIfPending returns 0 (no matching PENDING row).
            PendingSendResponse response = scheduledSendService.cancelSend(unknownId);

            assertEquals("sent", response.status());
        }
    }

    @Nested
    @DisplayName("Delivery")
    class Delivery {
        @Test
        @DisplayName("email is delivered after delay expires (only when claim succeeds)")
        void deliveryAfterDelay() throws InterruptedException {
            SendEmailRequest request = new SendEmailRequest(
                    "to@example.com", List.of("cc@example.com"), null,
                    "Test Subject", "<p>HTML</p>", "Body text");
            when(pendingSendRepository.markSentIfPending(any())).thenReturn(1);

            PendingSendResponse queued = scheduledSendService.queueSend(testAccount, request, null);

            Thread.sleep(12_000);

            verify(sendService, times(1)).sendEmail(eq(testAccount), eq(request), isNull());
            assertFalse(scheduledSendService.isPending(queued.sendId()));
        }
    }

    @Nested
    @DisplayName("Startup recovery")
    class Recovery {

        private PendingSend pendingRow(UUID id, LocalDateTime sendAt) {
            return PendingSend.builder()
                    .id(id)
                    .accountId(testAccount.getId())
                    .toAddress("to@example.com")
                    .subject("Recovered")
                    .bodyText("Body")
                    .sendAt(sendAt)
                    .status(PendingSend.PENDING)
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build();
        }

        @Test
        @DisplayName("past-due PENDING row is delivered immediately on startup")
        void deliversPastDueOnStartup() throws InterruptedException {
            UUID id = UUID.randomUUID();
            var row = pendingRow(id, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5));

            when(pendingSendRepository.findByStatus(PendingSend.PENDING)).thenReturn(List.of(row));
            when(pendingSendRepository.findById(id)).thenReturn(Optional.of(row));
            when(accountRepository.findById(testAccount.getId())).thenReturn(Optional.of(testAccount));
            when(pendingSendRepository.markSentIfPending(id)).thenReturn(1);

            scheduledSendService.recoverPendingSends();
            Thread.sleep(1_200);

            verify(sendService, times(1)).sendEmail(eq(testAccount), any(), isNull());
        }

        @Test
        @DisplayName("does NOT double-send when the claim fails (already sent/cancelled)")
        void noDoubleSendWhenClaimFails() throws InterruptedException {
            UUID id = UUID.randomUUID();
            var row = pendingRow(id, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5));

            when(pendingSendRepository.findByStatus(PendingSend.PENDING)).thenReturn(List.of(row));
            when(pendingSendRepository.findById(id)).thenReturn(Optional.of(row));
            when(accountRepository.findById(testAccount.getId())).thenReturn(Optional.of(testAccount));
            when(pendingSendRepository.markSentIfPending(id)).thenReturn(0); // claim lost

            scheduledSendService.recoverPendingSends();
            Thread.sleep(1_200);

            verify(sendService, never()).sendEmail(any(), any(), any());
        }

        @Test
        @DisplayName("no pending rows → nothing scheduled")
        void noPendingRows() {
            when(pendingSendRepository.findByStatus(PendingSend.PENDING)).thenReturn(List.of());
            scheduledSendService.recoverPendingSends();
            verify(accountRepository, never()).findById(any());
        }
    }
}
