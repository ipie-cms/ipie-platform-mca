package in.gov.ipie.common.testing.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

import in.gov.ipie.common.core.PlatformOverride;
import in.gov.ipie.common.events.idempotency.ProcessedEventStore;
import in.gov.ipie.common.events.outbox.OutboxStore;

import in.gov.ipie.common.core.exception.IpieException;

/**
 * The layering rules every iPIE service must enforce (master standards doc, section 16), plus the
 * binding rules that stop a service from quietly re-inventing something {@code ipie-common-libs}
 * already provides (master standards doc binding rules, Section 13). A service's own ArchUnit
 * test applies these against its own base package, e.g.:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "in.gov.ipie.service.template")
 * class ArchitectureTest {
 *     @ArchTest
 *     static final ArchRule controllers_do_not_access_repositories =
 *             LayeredArchitectureRules.controllersDoNotAccessRepositoriesDirectly("in.gov.ipie.service.template");
 * }
 * }</pre>
 *
 * <p>Since the 2026-07-21 flattening of each service's package layout (no more
 * {@code api}/{@code application}/{@code domain}/{@code infrastructure} wrapper folders - every
 * concern is its own top-level package: {@code controller}, {@code service}, {@code repository},
 * {@code persistence}, ...), these rules can no longer key off one {@code ..layer..} glob per
 * side. Each rule below lists the concrete top-level packages that make up each side instead.
 */
public final class LayeredArchitectureRules {

    /** A service's own infrastructure/adapter packages, now flattened out of one {@code infrastructure} wrapper. */
    private static final String[] INFRASTRUCTURE_PACKAGES = {
            "persistence", "messaging", "storage", "scanning", "search", "configuration", "idempotency", "integration",
    };

    /** A service's own application/domain-side packages, now flattened out of {@code application}/{@code domain}. */
    private static final String[] APPLICATION_PACKAGES = {"service", "command", "query", "domain"};

    /** A service's own API-side packages, now flattened out of one {@code api} wrapper. */
    private static final String[] API_PACKAGES = {"controller", "mapper", "dto"};

    private LayeredArchitectureRules() {
    }

    public static ArchRule controllersDoNotAccessRepositoriesDirectly(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + "..controller..")
                .should().dependOnClassesThat().resideInAPackage(basePackage + "..repository..")
                .because("controllers must not call repositories directly - business logic belongs in "
                        + "the service layer (master standards doc, 5.1/5.2)");
    }

    public static ArchRule domainDoesNotDependOnInfrastructure(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + "..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(packagesUnder(basePackage, INFRASTRUCTURE_PACKAGES))
                .because("domain code must not depend on infrastructure code (master standards doc, section 16)");
    }

    public static ArchRule applicationDoesNotDependOnApi(String basePackage) {
        return noClasses().that().resideInAnyPackage(packagesUnder(basePackage, APPLICATION_PACKAGES))
                .should().dependOnClassesThat().resideInAnyPackage(packagesUnder(basePackage, API_PACKAGES))
                .because("application/domain code must not depend upward on the API layer");
    }

    public static ArchRule apiDoesNotExposePersistenceEntities(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + "..controller..")
                .should().dependOnClassesThat().resideInAPackage(basePackage + "..persistence..")
                .because("public APIs must not expose persistence entities - use request/response DTOs "
                        + "(master standards doc, 5.2/section 16)");
    }

    /**
     * Every business/domain exception must extend {@link IpieException} - not raw
     * {@code RuntimeException}/{@code Exception} or an unrelated hierarchy - so common-web's
     * {@code GlobalExceptionHandler} can map it to the one {@code ApiError} shape without each
     * service needing its own translation logic (master standards doc binding rules, Section 13).
     */
    public static ArchRule exceptionsExtendIpieException(String basePackage) {
        return classes().that().resideInAPackage(basePackage + "..")
                .and().haveSimpleNameEndingWith("Exception")
                .should().beAssignableTo(IpieException.class)
                .because("business/domain exceptions must extend IpieException from common-core so "
                        + "GlobalExceptionHandler can map them to the standard ApiError shape - do not "
                        + "throw raw RuntimeException or build a parallel exception hierarchy "
                        + "(master standards doc binding rules, Section 13)");
    }

    /**
     * The one API error shape and exception-to-HTTP mapping lives in common-web's
     * {@code GlobalExceptionHandler}. A second {@code @ControllerAdvice}/{@code @RestControllerAdvice}
     * in a service creates a competing, undefined-precedence error handler instead of extending the
     * shared one (master standards doc binding rules, Section 13).
     */
    public static ArchRule noCompetingControllerAdvice(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + "..")
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice")
                .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                .because("a service must not define its own @ControllerAdvice/@RestControllerAdvice - "
                        + "extend or configure common-web's GlobalExceptionHandler instead "
                        + "(master standards doc binding rules, Section 13)");
    }

    /**
     * Business/application code must publish events by writing to {@code OutboxStore} inside its
     * own transaction, never by calling {@code EventPublisher} directly - a direct call is a
     * dual-write race between the database and the broker (master standards doc binding rules,
     * Section 13). Only the outbox relay (infrastructure) may depend on {@code EventPublisher}.
     */
    public static ArchRule applicationDoesNotCallEventPublisherDirectly(String basePackage) {
        return noClasses().that().resideInAnyPackage(packagesUnder(basePackage, APPLICATION_PACKAGES))
                .should().dependOnClassesThat().resideInAPackage("in.gov.ipie.common.events.publisher..")
                .because("business/application code must write to OutboxStore, never call "
                        + "EventPublisher directly - that is a dual-write risk between the database "
                        + "and the broker; only the outbox relay may depend on EventPublisher "
                        + "(master standards doc binding rules, Section 13)");
    }

    /**
     * A service does not hand-write an adapter for a platform port that the platform already
     * implements. {@code OutboxStore} and {@code ProcessedEventStore} each had one correct JPA
     * implementation and four byte-identical copies of it, so a change to the port meant four edits
     * and the copies could drift without anyone seeing it.
     *
     * <p>The service still owns what genuinely differs - which table the rows live in - by declaring
     * an entity and pointing the platform's store at it. A service that must replace the
     * implementation outright says so with {@code @PlatformOverride}, which keeps the exception
     * deliberate and visible rather than silent.
     */
    public static ArchRule noHandWrittenPlatformPortAdapters(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + "..")
                .and().areNotAnnotatedWith(PlatformOverride.class)
                .should().implement(OutboxStore.class)
                .orShould().implement(ProcessedEventStore.class)
                .because("the platform ships the JPA implementation of these ports - declare an "
                        + "entity for your table and pass it to JpaOutboxStore/JpaProcessedEventStore "
                        + "instead of copying the adapter, or annotate the class @PlatformOverride "
                        + "with the reason (master standards doc binding rules, Section 13)");
    }

    private static String[] packagesUnder(String basePackage, String[] packageNames) {
        String[] result = new String[packageNames.length];
        for (int i = 0; i < packageNames.length; i++) {
            result[i] = basePackage + ".." + packageNames[i] + "..";
        }
        return result;
    }
}
