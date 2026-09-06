package in.gov.ipie.common.utils.exception;

/**
 * Generic {@link Throwable} inspection helpers - unrelated to {@code common-core}'s exception
 * hierarchy ({@code IpieException}, {@code ErrorCode}, {@code FieldError}), which models
 * business/API errors rather than inspecting arbitrary caught exceptions.
 */
public final class ExceptionUtils {

    private ExceptionUtils() {
    }

    /** Walks {@link Throwable#getCause()} to the innermost exception (self, if there is no cause). */
    public static Throwable getRootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** Renders a throwable and its cause chain the same shape as an uncaught-exception dump, without ever printing to the console. */
    public static String stackTraceToString(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        String prefix = "";
        while (current != null) {
            builder.append(prefix).append(current).append(System.lineSeparator());
            for (StackTraceElement element : current.getStackTrace()) {
                builder.append("\tat ").append(element).append(System.lineSeparator());
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
            prefix = "Caused by: ";
        }
        return builder.toString();
    }

    /** The first exception in the cause chain (inclusive) assignable to {@code type}, if any. */
    public static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return null;
    }
}
