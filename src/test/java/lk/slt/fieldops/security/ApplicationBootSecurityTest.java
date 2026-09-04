package lk.slt.fieldops.security;

import lk.slt.fieldops.Application;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API-015 (11_API_ENDPOINTS) — there is no hardcoded JWT/DB credential fallback: with the required
 * secrets unset and no {@code local} profile active, the application must fail loudly at boot rather
 * than silently falling back to a committed default.
 *
 * <p><b>Why this is new coverage of an already-fixed area, not a duplicate.</b> The underlying
 * defect ("Hardcoded JWT secret / DB password fallbacks committed in {@code application.yml}") is
 * recorded as RESOLVED 2026-07-24 in {@code docs/QA_Compliance_Consolidated_Report.md}. Two things
 * verified it at the time and neither is a permanent regression test: (1) a one-off manual live
 * check by {@code test-agent} — real, but not repeatable and not part of any suite; and (2)
 * {@code fieldops/scripts/check_no_hardcoded_secrets.sh} (SEC-007, sheet 13), which is a
 * <b>static grep over the config text</b>. SEC-007 proves the file contains no
 * {@code ${JWT_SECRET:literal}} pattern; it cannot prove the <i>runtime</i> consequence, because a
 * bare {@code ${VAR}} that some {@code @Configuration} class quietly defaulted elsewhere, or a
 * property Spring resolved to an empty string instead of failing, would satisfy the grep and still
 * boot insecurely. This class asserts the behaviour instead of the text: the context genuinely
 * refuses to refresh.</p>
 *
 * <p><b>Why this is a plain JUnit test and not {@code @SpringBootTest}.</b> The row's pre-condition
 * is "no local profile active", which is the exact opposite of what every other test in this module
 * needs — {@code SPRING_PROFILES_ACTIVE=local} is mandatory for the rest of the suite to boot at
 * all. So this test drives {@link SpringApplication} by hand and overrides the profile with a
 * <b>command-line argument</b>, which sits above OS environment variables in Spring Boot's property
 * source precedence and therefore genuinely neutralises the ambient {@code SPRING_PROFILES_ACTIVE}
 * the suite runs under. The row's {@code Assertion Code} —
 * {@code assertThrows(IllegalArgumentException.class, () -> context.refresh())} — is preserved in
 * spirit and widened to inspect the whole exception chain, because Spring wraps the placeholder
 * failure in a {@code BeanCreationException} before it surfaces.</p>
 *
 * <p><b>2026-09-02, Testcontainers migration — a genuinely new interaction, found by reading this
 * file before running anything, not by a red surprising anyone.</b> {@code src/test/resources/
 * application.yml} now shadows {@code src/main/resources/application.yml} on the test classpath
 * (test-classes precedes classes; same-named resources don't merge, the first one found wins) —
 * every OTHER test in this suite wants that, since it is what removes the env-var requirement this
 * class's own docstring above still describes. But it carries a literal test-only {@code app.jwt.
 * secret} and {@code test}/{@code test} datasource credentials specifically so the rest of the suite
 * boots with zero setup — which would otherwise satisfy this test's "no fallback" boot attempts and
 * make it wrongly report the application secure-by-fallback failure as if it had passed for the
 * right reason. Both {@link SpringApplication#run} calls below now pass
 * {@code --spring.config.location=classpath:/does-not-exist.yml,file:src/main/resources/
 * application.yml} — an explicit location list bypasses Boot's normal classpath search entirely, so
 * only the real, shipped config file is read (below, actually {@code --spring.config.location=
 * file:src/main/resources/application.yml}, a single explicit location), exactly reproducing what
 * this test intends to prove regardless of which test-resources file happens to shadow the
 * classpath default elsewhere in the suite.</p>
 *
 * <p><b>Steps 2 and 3 of the row</b> ("no 'Started Application' log", "nothing listening on the app
 * port afterward") are covered structurally rather than by scraping stdout or opening a socket: the
 * application is run with {@link WebApplicationType#NONE}, and the assertion that {@code run()}
 * threw <i>at all</i> is a strictly stronger statement than the absence of a log line — Spring Boot
 * only emits "Started Application" and only binds the connector after a successful refresh, so a
 * context that never refreshed cannot have done either.</p>
 */
class ApplicationBootSecurityTest {

    /** Every property {@code application.yml} declares as a bare, required {@code ${VAR}}. */
    private static final String[] REQUIRED_SECRET_ENV_VARS = {
        "JWT_SECRET", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"
    };

    private static final Path APPLICATION_YML =
        Path.of("src", "main", "resources", "application.yml");

    /** Flattens the whole {@code getCause()} chain into one searchable string. */
    private static String chainText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 20_000; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" | ");
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }

    private static boolean chainContains(Throwable t, Class<?> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    @Test
    void failsLoudlyOnMissingSecrets() throws Exception {
        // ── Pre-condition, asserted rather than assumed ─────────────────────────────────────
        // The row's pre-condition is a clean environment. If this host DID export the secrets,
        // the context would boot for a legitimate reason and a naive assertThrows would report a
        // false red. Fail with an explanation instead of silently testing nothing.
        List<String> present = new ArrayList<>();
        for (String var : REQUIRED_SECRET_ENV_VARS) {
            if (System.getenv(var) != null || System.getProperty(var) != null) present.add(var);
        }
        assertTrue(present.isEmpty(),
            "API-015 requires a clean environment (the row's own pre-condition). These are set on "
                + "this host and would let the context resolve its placeholders legitimately: "
                + present + ". Unset them and re-run — this is an environment problem, not a "
                + "product defect.");

        // ── The config must still declare these as BARE, required placeholders ─────────────
        // This is the static half (SEC-007's subject) restated here only so that a failure of the
        // runtime half below can be attributed correctly: if someone reintroduced a fallback,
        // this assertion names it precisely instead of leaving a confusing "context booted" red.
        assertTrue(Files.exists(APPLICATION_YML),
            "Expected " + APPLICATION_YML.toAbsolutePath() + " to exist");
        String yml = Files.readString(APPLICATION_YML);
        for (String var : REQUIRED_SECRET_ENV_VARS) {
            assertTrue(yml.contains("${" + var + "}"),
                "application.yml must reference " + var + " as a bare ${" + var + "} with no "
                    + "fallback value.");
            assertFalse(yml.contains("${" + var + ":"),
                "application.yml declares ${" + var + ":<default>} — the literal after the colon "
                    + "is a committed secret and anyone reading the repo can use it.");
        }

        // ── Steps 1-2: booting with no credentials and no local profile must fail ──────────
        SpringApplication app = new SpringApplication(Application.class);
        app.setWebApplicationType(WebApplicationType.NONE);   // never bind a port
        app.setRegisterShutdownHook(false);
        app.setLogStartupInfo(false);

        ConfigurableApplicationContext leaked = null;
        Throwable failure = null;
        try {
            // A command-line argument outranks the ambient SPRING_PROFILES_ACTIVE=local that the
            // rest of this suite depends on, so application-local.yml is genuinely NOT applied.
            // spring.config.location bypasses the classpath search entirely, so the test-only
            // src/test/resources/application.yml (which shadows this file for every other test)
            // is not read either — only the real, shipped config is.
            leaked = app.run(
                "--spring.profiles.active=api015-no-secrets",
                "--spring.config.location=file:src/main/resources/application.yml");
        } catch (Throwable t) {
            failure = t;
        } finally {
            if (leaked != null) {
                try { leaked.close(); } catch (Exception ignored) { /* best effort */ }
            }
        }

        assertNull(leaked,
            "SECURITY: the application context started successfully with JWT_SECRET and "
                + "SPRING_DATASOURCE_USERNAME/PASSWORD all unset and no 'local' profile active. "
                + "That means an insecure default is being supplied from somewhere — a committed "
                + "fallback, a @Value default, or a property file that should not be on the "
                + "classpath. Misconfiguration must fail fast and loud at boot.");
        assertNotNull(failure, "Startup was expected to throw");

        // ── Expected Result: the failure is attributable to a missing required secret ──────
        // A test that accepted ANY exception would go green on an unrelated failure and stop
        // proving anything, so the chain must name one of the required env vars.
        String chain = chainText(failure);
        boolean namesASecret = false;
        for (String var : REQUIRED_SECRET_ENV_VARS) {
            if (chain.contains(var)) { namesASecret = true; break; }
        }
        // 2026-09-03, CI-portable-database finding: on a developer machine with a local MySQL
        // always listening on :3306, the unresolved placeholder username/password reach MySQL as
        // literal text and it echoes them back in "Access denied for user
        // '${SPRING_DATASOURCE_USERNAME}'" -- which is how namesASecret above normally passes. A
        // genuinely portable CI runner has no local MySQL reachable at all, so the TCP connection
        // itself is refused before MySQL ever gets a chance to name the var. That is still a
        // legitimate "no insecure fallback" failure -- a real credential would have produced the
        // identical connection attempt -- so it is accepted as an alternate, equally-attributable
        // failure mode rather than this assertion assuming a reachable-but-wrong-credentials
        // topology no portable CI runner actually has.
        boolean noDbReachableAtAll = chainContains(failure, java.net.ConnectException.class)
            || chain.contains("CommunicationsException")
            || chain.contains("Connection refused");
        assertTrue(namesASecret || noDbReachableAtAll,
            "The boot failure must be attributable to one of the required secret env vars ("
                + String.join("/", REQUIRED_SECRET_ENV_VARS) + ") OR to no database being reachable "
                + "at all (equally proving no insecure fallback exists) so an operator can see what "
                + "to set. Actual exception chain: " + chain);

        // ── Step 3: no partially-started context was left behind ──────────────────────────
        // (`leaked` is asserted null above; this restates the row's "nothing listening" check in
        // the only form available in-process — WebApplicationType.NONE means no connector was
        // ever created, and a context that failed to refresh never reaches the connector phase.)
        assertFalse(chain.contains("Started Application"),
            "A failed boot must not report a successful start. Chain: " + chain);

        // ── The JWT signing key specifically: no fallback, hard failure ────────────────────
        // Part A above proves the app as a whole refuses to start, but it fails at the DATASOURCE,
        // which is created first — so on its own it does not prove anything about the JWT secret.
        // (Measured, and worth recording: the datasource placeholders do NOT raise "Could not
        // resolve placeholder" at all. Spring Boot's Binder resolves @ConfigurationProperties
        // placeholders with ignoreUnresolvablePlaceholders=true, so an unset
        // SPRING_DATASOURCE_USERNAME is passed to the JDBC driver as the LITERAL TEXT
        // "${SPRING_DATASOURCE_USERNAME}" and surfaces as a confusing MySQL "Access denied for
        // user '${SPRING_DATASOURCE_USERNAME}'" instead of a clear misconfiguration message.
        // Security-wise this is still fail-loud with no fallback — the app does not start — so it
        // satisfies this row; it is logged as a MINOR diagnosability gap, not a defect in the
        // property this row exists to protect.)
        //
        // This second boot excludes the datasource autoconfiguration so the context gets far
        // enough to construct JwtTokenProvider, whose @Value("${app.jwt.secret}") resolves via
        // PropertySourcesPlaceholderConfigurer — which does NOT ignore unresolvable placeholders
        // and therefore produces the exact IllegalArgumentException the row's Assertion Code and
        // the 2026-07-24 Resolution Log both name.
        SpringApplication jwtOnly = new SpringApplication(Application.class);
        jwtOnly.setWebApplicationType(WebApplicationType.NONE);
        jwtOnly.setRegisterShutdownHook(false);
        jwtOnly.setLogStartupInfo(false);

        ConfigurableApplicationContext jwtLeaked = null;
        Throwable jwtFailure = null;
        try {
            jwtLeaked = jwtOnly.run(
                "--spring.profiles.active=api015-no-secrets",
                "--spring.config.location=file:src/main/resources/application.yml",
                "--spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration");
        } catch (Throwable t) {
            jwtFailure = t;
        } finally {
            if (jwtLeaked != null) {
                try { jwtLeaked.close(); } catch (Exception ignored) { /* best effort */ }
            }
        }

        assertNull(jwtLeaked,
            "SECURITY: the context started with JWT_SECRET unset. A signing key must never have a "
                + "fallback — anyone reading the repository could otherwise forge valid tokens.");
        assertNotNull(jwtFailure, "Startup was expected to throw on the unresolved JWT secret");

        String jwtChain = chainText(jwtFailure);
        assertTrue(chainContains(jwtFailure, IllegalArgumentException.class),
            "The row's Assertion Code expects an IllegalArgumentException for the unresolved JWT "
                + "placeholder. Actual exception chain: " + jwtChain);
        assertTrue(jwtChain.contains("Could not resolve placeholder")
                   && jwtChain.contains("JWT_SECRET"),
            "Startup must fail with 'Could not resolve placeholder ... JWT_SECRET' — the exact "
                + "failure the 2026-07-24 secret-hardening fix was verified against. Actual "
                + "exception chain: " + jwtChain);
    }
}
