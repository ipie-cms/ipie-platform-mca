package in.gov.ipie.smoke;

/** A deliberately clean class - Checkstyle should find nothing to report here. */
public final class Clean {

    private Clean() {
    }

    /**
     * Returns a greeting.
     *
     * @param name who to greet
     * @return the greeting
     */
    public static String greet(String name) {
        return "hello " + name;
    }
}
