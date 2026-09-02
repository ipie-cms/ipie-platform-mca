package in.gov.ipie.common.persistence.replica;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the read replica is, and whether to use one at all.
 *
 * <p>Bound from {@code ipie.datasource.replica.*}. Off unless {@code enabled} is explicitly true,
 * so a service that says nothing keeps exactly the single-database behaviour it has today.
 */
@ConfigurationProperties(prefix = "ipie.datasource.replica")
public class ReplicaDataSourceProperties {

    /**
     * Whether reads in a read-only transaction go to the replica.
     *
     * <p>Off by default. Turning it on is a deployment decision made by the Database Administrator
     * once replicas exist; it is not something a service opts into in its own source, which is why
     * this is a property rather than a code change.
     */
    private boolean enabled = false;

    /** JDBC URL of the replica. Required when {@link #enabled} is true; there is no default. */
    private String url;

    /** Replica credentials. Fall back to the primary's when unset, which is the usual case. */
    private String username;

    private String password;

    /**
     * Driver class. Left unset in almost every case - it is inferred from the URL, exactly as it is
     * for the primary.
     */
    private String driverClassName;

    /**
     * Maximum pool size for the replica.
     *
     * <p>Separate from the primary's on purpose. The two carry different traffic once reads are
     * split off, and sizing them together would mean tuning one to suit the other. Unset means
     * "use the same size as the primary".
     */
    private Integer maximumPoolSize;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public Integer getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(Integer maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }
}
