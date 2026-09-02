package in.gov.ipie.common.core.paging;

/**
 * Framework-agnostic paging request used by domain and application code. Infrastructure adapters
 * translate this to/from the persistence framework's own paging type (e.g. Spring Data's
 * {@code Pageable}) - domain/application code must not depend on that framework directly.
 */
public record PageRequest(int page, int size, String sortBy, SortDirection sortDirection) {

    public static final int MAX_PAGE_SIZE = 200;

    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size, null, SortDirection.ASC);
    }

    public enum SortDirection {
        ASC,
        DESC
    }
}
