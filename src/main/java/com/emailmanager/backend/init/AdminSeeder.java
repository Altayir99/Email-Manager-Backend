package com.emailmanager.backend.init;

import com.emailmanager.backend.user.User;
import com.emailmanager.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Private-use seeder: creates the single application account on first boot
 * if it doesn't already exist.
 *
 * Required env vars (prod):
 *   APP_USERNAME  — the account username
 *   APP_PASSWORD  — the account password (plain-text; stored BCrypt-hashed)
 *
 * Idempotent — skips if username already exists.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.username}")
    private String username;

    @Value("${app.admin.password}")
    private String rawPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByUsername(username)) {
            log.info("[Seeder] Account '{}' already exists — skipping.", username);
            return;
        }
        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(rawPassword))
                .build();
        userRepository.save(user);
        log.info("[Seeder] Account '{}' created successfully.", username);
    }
}
