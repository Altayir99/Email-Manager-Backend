package com.emailmanager.backend.emails.dto;

public record FolderDto(
        String name,
        String fullName,
        int unreadCount,
        int totalCount,
        boolean hasChildren
) {}
