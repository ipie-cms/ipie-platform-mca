package in.gov.ipie.common.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that deliberately replaces something the platform already ships, and says why.
 *
 * <p>The default is that a service does not write its own adapter for a platform port: if the port
 * is here, its one implementation is here too, and the service supplies configuration. That default
 * is enforced by {@code LayeredArchitectureRules.noHandWrittenPlatformPortAdapters}, and this
 * annotation is how a service opts out of it - deliberately, in writing, and visibly in review.
 *
 * <p>Not a suppression to reach for. A genuine override is a service whose storage or delivery
 * differs for a reason the platform cannot serve; "it was quicker to copy" is the case the rule
 * exists to catch.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PlatformOverride {

    /** Why this service must not use the platform's implementation. */
    String value();
}
