package com.pitsch.backend.auth;

import java.time.LocalDate;

import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.org.Organization;
import com.pitsch.backend.org.OrganizationRepository;
import com.pitsch.backend.org.OrganizationService;
import com.pitsch.backend.pitch.PitchService;
import com.pitsch.backend.task.Task;
import com.pitsch.backend.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo mode only (PITSCH_MODE=demo, which production refuses): creates a demo investor account and workspace with a
 * few manually entered sample pitches so the UI is not empty. No AI output is fabricated — briefs appear only after
 * an email is processed by the real agents. Never runs in production or development mode.
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    public static final String DEMO_EMAIL = "demo@pitsch.app";
    public static final String DEFAULT_DEMO_PASSWORD = "pitsch-demo-2026";
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final OrganizationService orgService;
    private final OrganizationRepository organizations;
    private final PitchService pitches;
    private final TaskRepository tasks;
    private final PitschProperties props;
    private final String password;

    public DemoDataSeeder(UserRepository users, PasswordHasher hasher, OrganizationService orgService,
                          OrganizationRepository organizations, PitchService pitches, TaskRepository tasks,
                          PitschProperties props,
                          @org.springframework.beans.factory.annotation.Value("${DEMO_PASSWORD:" + DEFAULT_DEMO_PASSWORD + "}") String password) {
        this.users = users;
        this.hasher = hasher;
        this.orgService = orgService;
        this.organizations = organizations;
        this.pitches = pitches;
        this.tasks = tasks;
        this.props = props;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!props.isDemo() || users.existsByEmailIgnoreCase(DEMO_EMAIL)) {
            return;
        }
        User demo = new User();
        demo.setName("Demo Investor");
        demo.setEmail(DEMO_EMAIL);
        demo.setPasswordHash(hasher.hash(password));
        demo.setEmailVerifiedAt(java.time.Instant.now());
        demo = users.save(demo);
        Organization org = orgService.createWorkspace(demo, "Demo Capital", props.getDefaultTimezone());
        org.setOnboardingCompletedAt(java.time.Instant.now());
        organizations.save(org);

        sample(org.getId(), demo.getId(), "Northwind Robotics", "Ada Byte", "ada@northwind.example", "https://northwind.example",
                "Robotics", "Seed", "Autonomous inventory robots for mid-size warehouses.", "SCREENING");
        sample(org.getId(), demo.getId(), "Lumen Health", "Grace Hopper", "grace@lumen.example", "https://lumen.example",
                "Healthtech", "Pre-seed", "Remote monitoring for post-operative care.", "NEW");
        sample(org.getId(), demo.getId(), "Quill Ledger", "Alan Key", "alan@quill.example", "https://quill.example",
                "Fintech", "Series A", "Automated bookkeeping for agencies.", "DILIGENCE");

        Task t = new Task();
        t.setOrganizationId(org.getId());
        t.setUserId(demo.getId());
        t.setCreatedByUserId(demo.getId());
        t.setAssigneeUserId(demo.getId());
        t.setTitle("Submit a sample pitch email to see the agents work");
        t.setDescription("Open Inbox → Import email. In demo mode, Gmail/Calendar/Sheets actions are simulated and labelled.");
        t.setPriority("HIGH");
        t.setDueDate(LocalDate.now().plusDays(1));
        tasks.save(t);
        log.info("Demo mode: created {} with a sample workspace (password from DEMO_PASSWORD or the documented default)", DEMO_EMAIL);
    }

    private void sample(Long orgId, Long userId, String company, String founder, String email, String website,
                        String sector, String stage, String oneLiner, String dealStage) {
        pitches.createManual(orgId, userId, new PitchService.PitchInput(company, founder, email, website, sector, stage,
                oneLiner, "Sample data created by demo mode.", null, dealStage, null));
    }
}
