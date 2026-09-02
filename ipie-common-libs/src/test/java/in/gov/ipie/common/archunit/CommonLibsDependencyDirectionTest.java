package in.gov.ipie.common.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Before 2026-07-20 this module was 13 independent Gradle projects, so {@code core} physically
 * could not depend on {@code web}/{@code security}/etc. - it simply never declared that project
 * dependency. Now that every package lives in one compilation unit, nothing stops a class in
 * {@code core} from importing one in a sibling package except this test - it re-encodes the same
 * dependency direction the old module graph enforced (every other package could depend on
 * {@code core}, {@code core} depended on nothing).
 */
@AnalyzeClasses(packages = "in.gov.ipie.common")
class CommonLibsDependencyDirectionTest {

    private static final String[] SIBLING_PACKAGES = {
            "in.gov.ipie.common.web..",
            "in.gov.ipie.common.security..",
            "in.gov.ipie.common.observability..",
            "in.gov.ipie.common.audit..",
            "in.gov.ipie.common.events..",
            "in.gov.ipie.common.resilience..",
            "in.gov.ipie.common.client..",
            "in.gov.ipie.common.utils..",
            "in.gov.ipie.common.filestorage..",
            "in.gov.ipie.common.cache..",
            "in.gov.ipie.common.session..",
            "in.gov.ipie.common.i18n..",
    };

    @ArchTest
    static final ArchRule core_does_not_depend_on_any_other_common_lib_package =
            noClasses().that().resideInAPackage("in.gov.ipie.common.core..")
                    .should().dependOnClassesThat().resideInAnyPackage(SIBLING_PACKAGES)
                    .because("core is the base of the dependency graph every other package may depend on - "
                            + "before the 2026-07-20 module merge this was a physical Gradle module boundary, "
                            + "now it is only this test");
}
