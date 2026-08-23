package io.gateway.oss.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "public_model_mapping")
public class PublicModelMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String alias;

    @Column(name = "public_model_id", nullable = false)
    private Long publicModelId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "client_overrides")
    private Map<String, Object> clientOverrides;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "public_model_id", insertable = false, updatable = false)
    private PublicModelEntity publicModel;

    public PublicModelMappingEntity() {
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public Long getPublicModelId() {
        return publicModelId;
    }

    public void setPublicModelId(Long publicModelId) {
        this.publicModelId = publicModelId;
    }

    public Map<String, Object> getClientOverrides() {
        return clientOverrides;
    }

    public void setClientOverrides(Map<String, Object> clientOverrides) {
        this.clientOverrides = clientOverrides;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public PublicModelEntity getPublicModel() {
        return publicModel;
    }

    public void setPublicModel(PublicModelEntity publicModel) {
        this.publicModel = publicModel;
    }
}
