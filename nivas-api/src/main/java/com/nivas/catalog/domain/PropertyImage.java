package com.nivas.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "property_images")
@EntityListeners(AuditingEntityListener.class)
public class PropertyImage {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "caption", length = 180)
    private String caption;

    /** Zero-based display order. Position 0 is the cover image. */
    @Column(name = "position", nullable = false)
    private short position;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PropertyImage() {
        // for JPA
    }

    public PropertyImage(String url, String caption, short position, Integer width, Integer height) {
        this.url = url;
        this.caption = caption;
        this.position = position;
        this.width = width;
        this.height = height;
    }

    public UUID getId() {
        return id;
    }

    public Property getProperty() {
        return property;
    }

    void setProperty(Property property) {
        this.property = property;
    }

    public String getUrl() {
        return url;
    }

    public String getCaption() {
        return caption;
    }

    public short getPosition() {
        return position;
    }

    public void setPosition(short position) {
        this.position = position;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isCover() {
        return position == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PropertyImage image) || id == null) {
            return false;
        }
        return id.equals(image.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
