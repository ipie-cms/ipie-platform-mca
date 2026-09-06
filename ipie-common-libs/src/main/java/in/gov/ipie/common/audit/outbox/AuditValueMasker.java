package in.gov.ipie.common.audit.outbox;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import in.gov.ipie.common.utils.masking.DataMasking;

/**
 * Masks known-PII field names before an {@code AuditEvent}'s {@code oldValue}/{@code newValue}
 * snapshot is persisted into {@code audit_trail}/{@code iam_audit_trail} - those columns hold a
 * full JSON snapshot of whatever entity an {@code @Auditable} action touched ({@code
 * AuditAspect}'s {@code oldValue()}/{@code newValue()} SpEL), which routinely includes personal
 * data (email, phone, a government id value) the master standards doc's DPDP Act masking rule
 * already requires never appear unmasked ({@link DataMasking}'s own Javadoc). That rule was
 * written with log lines in mind, but the same legal basis applies just as much to a queryable
 * database column - this closes that gap for the audit trail specifically, rather than leaving a
 * DB table as an unmasked side channel around a rule everything else already follows.
 *
 * <p>Works by field <em>name</em>, not Java type - {@code oldValue}/{@code newValue} can be any
 * entity shape ({@code User}, {@code Organisation}, {@code Role}, ...), so this walks the
 * serialized JSON tree and masks any object field whose key matches a known-sensitive name,
 * case-insensitively, recursing into nested objects/arrays. A field name not on the list passes
 * through unchanged - this is a deliberately explicit allowlist of known field names (matching
 * this codebase's actual domain models), not a guess-based heuristic, to keep false negatives
 * (a sensitive field slipping through unmasked) and false positives (an unrelated field masked
 * unnecessarily, e.g. a genuinely public {@code CIN} under {@code Organisation.idValue}) both
 * predictable rather than a matter of trusting a regex to get it right.
 */
public final class AuditValueMasker {

    private static final Set<String> EMAIL_FIELDS = Set.of("email", "contactemail");
    private static final Set<String> PHONE_FIELDS = Set.of("phonenumber", "contactnumber", "mobilenumber");
    // Credentials and one-time secrets. This list is a backstop, not the control: a credential is
    // not supposed to reach an audit event at all (see the standards' credential-handling rule -
    // a password may travel only on a synchronous call to the identity provider, never onto the
    // outbox, a broker, a table or a log). Anything caught here means something upstream put a
    // secret somewhere it should not be, so treat a newly-masked field as a bug to trace, not as
    // the protection working as intended.
    //
    // Kept deliberately wide across the spellings this codebase and its DTOs actually use -
    // an unmasked credential is a far worse outcome than an over-masked field, and the cost of a
    // false positive here is one unreadable audit value.
    private static final Set<String> SECRET_FIELDS = Set.of(
            "verificationtoken", "password", "secret", "clientsecret",
            "newpassword", "oldpassword", "currentpassword", "confirmpassword",
            "passwordhash", "passwordconfirmation",
            // "pin" is deliberately ABSENT: in this domain it is the postal code on an address
            // (User.pin, EntityDraftDetails.pin), not a secret. Adding it would silently blank a
            // legitimate address field in every audit record - exactly the false positive this
            // list's allowlist approach exists to avoid. "mpin" is the secret one.
            "otp", "otpcode", "emailotpcode", "mpin",
            "token", "accesstoken", "refreshtoken", "idtoken", "bearertoken",
            "apikey", "privatekey", "credential", "credentials", "authorization");
    // idValue is masked, not skipped - Organisation.idValue can be a PAN (definitely PII); it can
    // also be a CIN (public record), but this class has no way to know which without also
    // inspecting the sibling idType field, so it errs toward masking the government-id value
    // rather than risking a PAN slipping through unmasked.
    private static final Set<String> GOVERNMENT_ID_FIELDS = Set.of("idvalue");

    private AuditValueMasker() {
    }

    /** Serializes {@code value} to JSON with known-PII field values masked, or {@code null} if {@code value} is {@code null}. */
    public static String maskAndSerialize(Object value, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        JsonNode tree = objectMapper.valueToTree(value);
        maskRecursively(tree);
        try {
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize a masked audit-trail value", e);
        }
    }

    private static void maskRecursively(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode fieldValue = field.getValue();
                if (fieldValue.isTextual()) {
                    String masked = maskIfSensitive(field.getKey(), fieldValue.asText());
                    if (masked != null) {
                        objectNode.set(field.getKey(), new TextNode(masked));
                        continue;
                    }
                }
                maskRecursively(fieldValue);
            }
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(AuditValueMasker::maskRecursively);
        }
    }

    /** @return the masked value if {@code fieldName} is a known-sensitive field, otherwise {@code null} (leave as-is) */
    private static String maskIfSensitive(String fieldName, String value) {
        String key = fieldName.toLowerCase(Locale.ROOT);
        if (EMAIL_FIELDS.contains(key)) {
            return DataMasking.maskEmail(value);
        }
        if (PHONE_FIELDS.contains(key)) {
            return DataMasking.mask(value, 4);
        }
        if (SECRET_FIELDS.contains(key)) {
            return DataMasking.mask(value, 0);
        }
        if (GOVERNMENT_ID_FIELDS.contains(key)) {
            return DataMasking.mask(value, 4);
        }
        return null;
    }
}
