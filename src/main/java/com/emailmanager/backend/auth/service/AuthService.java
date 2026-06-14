package com.emailmanager.backend.auth.service;

import com.emailmanager.backend.auth.dto.AuthResponse;
import com.emailmanager.backend.auth.dto.LoginRequest;
import com.emailmanager.backend.security.JwtService;
import com.emailmanager.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        var user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        String token = jwtService.generateToken(user.getUsername());
        return new AuthResponse(token, user.getUsername());
    }
}
