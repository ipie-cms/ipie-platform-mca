package in.gov.ipie.common.persistence;

import in.gov.ipie.common.core.exception.CommonErrorCode;
import in.gov.ipie.common.core.exception.IpieException;

/**
 * A row was rejected because its <em>primary key</em> already existed - not because of anything the
 * caller sent.
 *
 * <p>Distinct from {@code ConflictException} on purpose. A conflict is the caller's problem and the
 * same request will fail again; this is the platform's problem and an identical request with a fresh
 * id would succeed. Reporting the two the same way is what makes an id collision look like a
 * duplicate email, and sends whoever is debugging it to the wrong place entirely.
 *
 * <p>With version-7 ids this should never be thrown: a collision needs two identical 74-bit draws
 * inside the same millisecond. That is exactly why it deserves its own type - if it ever appears in
 * a log, the interesting fact is not that an insert failed but that something about id generation is
 * wrong. Seeded rows with hand-written literal ids are the realistic cause, not the generator.
 */
public class IdCollisionException extends IpieException {

    public IdCollisionException(String constraintName) {
        this(constraintName, null);
    }

    /**
     * Carries the database failure as its cause, so the one log line written for a collision holds
     * the statement that failed. Without it the trace stops at this exception, which names the
     * constraint and nothing else - and the whole point of the type is that whoever reads it is
     * investigating id generation, not the request.
     */
    public IdCollisionException(String constraintName, Throwable cause) {
        super(CommonErrorCode.CONFLICT,
                "The generated identifier collided with an existing row (constraint '" + constraintName
                        + "'). Retrying with a newly generated id is safe.",
                cause);
    }
}
