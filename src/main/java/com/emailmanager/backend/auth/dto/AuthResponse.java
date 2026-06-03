package com.emailmanager.backend.auth.dto;

public record AuthResponse(String token, String username, String email) {}
