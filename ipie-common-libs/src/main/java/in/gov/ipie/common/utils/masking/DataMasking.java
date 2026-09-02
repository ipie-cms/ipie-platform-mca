package in.gov.ipie.common.utils.masking;

/**
 * Masks sensitive values before they are logged, displayed or written to a non-production
 * environment - passwords, tokens, Aadhaar numbers, PAN values and other sensitive data must
 * never appear unmasked (master standards doc, section 10's Guidelines subsection; the DPDP
 * Act, 2023 is the actual legal basis for this, not just good practice). Business modules should
 * call these instead of writing ad hoc masking logic.
 */
public final class DataMasking {

    private DataMasking() {
    }

    /** {@code jo****@example.com} - keeps the first two local-part characters and the whole domain. */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return mask(email, 0);
        }
        String localPart = email.substring(0, at);
        String domain = email.substring(at);
        String visible = localPart.length() <= 2 ? localPart : localPart.substring(0, 2);
        return visible + "*".repeat(Math.max(localPart.length() - visible.length(), 3)) + domain;
    }

    /** {@code ABCDE****F} - India PAN: keep the first five and last character, mask the rest. */
    public static String maskPan(String pan) {
        if (pan == null || pan.length() != 10) {
            return mask(pan, 0);
        }
        return pan.substring(0, 5) + "****" + pan.substring(9);
    }

    /** {@code XXXX XXXX 1234} - India Aadhaar: only the last four digits are ever shown. */
    public static String maskAadhaar(String aadhaar) {
        if (aadhaar == null) {
            return null;
        }
        String digitsOnly = aadhaar.replaceAll("\\D", "");
        if (digitsOnly.length() != 12) {
            return mask(aadhaar, 0);
        }
        return "XXXX XXXX " + digitsOnly.substring(8);
    }

    /** {@code ******1234} - keeps only the last {@code visibleChars} characters. */
    public static String mask(String value, int visibleChars) {
        if (value == null) {
            return null;
        }
        int visible = Math.max(visibleChars, 0);
        if (value.length() <= visible) {
            return "*".repeat(value.length());
        }
        int maskedLength = value.length() - visible;
        return "*".repeat(maskedLength) + value.substring(maskedLength);
    }
}
