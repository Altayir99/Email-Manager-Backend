package com.emailmanager.backend.init;

import com.emailmanager.backend.user.User;
import com.emailmanager.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Phase 1.2 security fix: @Profile("dev") ensures admin/admin123 is NEVER
 * seeded in production. Only runs when Spring profile is "dev".
 *
 * To seed manually in prod, use the POST /auth/register endpoint or a
 * one-time migration script.
 */
@Component
@Profile("dev")   // ← NEVER runs in prod
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.existsByUsername("admin")) {
            User admin = User.builder()
                    .username("admin")
                    .email("admin@emailmanager.local")
                    .password(passwordEncoder.encode("admin123"))
                    .build();
            userRepository.save(admin);
            log.info("[Dev] Admin user seeded — this never runs in prod");
        }
    }
}
