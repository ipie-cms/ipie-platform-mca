package in.gov.ipie.common.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.Getter;
import lombok.Setter;

/**
 * Shared audit + soft-delete columns for every JPA entity platform-wide (master standards doc,
 * section 8) - {@code createdAt/createdBy/updatedAt/updatedBy/version} plus
 * {@code isActive/deletedAt/deletedBy}. A subclass keeps its own {@code @Id} field; this class
 * owns none.
 *
 * <p>{@code @EntityListeners} here is inherited by every subclass automatically - a subclass must
 * not repeat it. Requires {@code spring.jpa.properties.hibernate.ejb.interceptor}-free auditing to
 * be enabled the usual way (a service's own {@code @EnableJpaAuditing} configuration, e.g.
 * {@code JpaAuditingConfig}), same as before this class existed.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class AuditableJpaEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;
}
