package in.gov.ipie.common.utils.validation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Format validators for values with no natural home in Jakarta Bean Validation - India-specific
 * identifiers and simple shape checks. Complements {@code masking.DataMasking}, which formats
 * these same identifiers for display rather than validating them. Not a replacement for
 * {@code common-core}'s {@code ValidationFailedException}/{@code FieldError}, which carry a
 * failed validation result across a service boundary - these are the pure predicate checks that
 * feed into that.
 */
public final class ValidationUtils {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    // https://uidai.gov.in - 10 digits, does not start with 0 or 1
    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");
    // Income Tax Dept PAN format: AAAAA9999A
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    // UIDAI Aadhaar format: 12 digits, does not start with 0 or 1; spaces/hyphens allowed as separators
    private static final Pattern AADHAAR = Pattern.compile("^[2-9]\\d{11}$");
    private static final Pattern PINCODE = Pattern.compile("^[1-9]\\d{5}$");

    private ValidationUtils() {
    }

    public static boolean isValidEmail(String value) {
        return value != null && EMAIL.matcher(value).matches();
    }

    public static boolean isValidIndianMobile(String value) {
        return value != null && INDIAN_MOBILE.matcher(value).matches();
    }

    public static boolean isValidPan(String value) {
        return value != null && PAN.matcher(value).matches();
    }

    public static boolean isValidAadhaar(String value) {
        if (value == null) {
            return false;
        }
        String digitsOnly = value.replaceAll("[\\s-]", "");
        return AADHAAR.matcher(digitsOnly).matches();
    }

    public static boolean isValidPincode(String value) {
        return value != null && PINCODE.matcher(value).matches();
    }

    public static boolean isValidUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isInRange(int value, int minInclusive, int maxInclusive) {
        return value >= minInclusive && value <= maxInclusive;
    }
}
