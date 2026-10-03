package com.pitsch.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Creates the demo account the login page advertises (demo@pitsch.com / pitsch123). */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    public static final String DEMO_EMAIL = "demo@pitsch.com";
    public static final String DEMO_PASSWORD = "pitsch123";
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final boolean enabled;

    public DemoDataSeeder(UserRepository users, PasswordHasher hasher,
                          @Value("${pitsch.demo.seed-user:true}") boolean enabled) {
        this.users = users;
        this.hasher = hasher;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled || users.existsByEmailIgnoreCase(DEMO_EMAIL)) {
            return;
        }
        User demo = new User();
        demo.setName("Demo Investor");
        demo.setEmail(DEMO_EMAIL);
        demo.setPasswordHash(hasher.hash(DEMO_PASSWORD));
        users.save(demo);
        log.info("Created demo user {} (set SEED_DEMO_USER=false to disable)", DEMO_EMAIL);
    }
}
