package com.hipster.release.domain;

import com.hipster.descriptor.domain.Descriptor;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "release_descriptors")
public class ReleaseDescriptor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id", nullable = false)
    private Release release;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "descriptor_id", nullable = false)
    private Descriptor descriptor;

    @Column(name = "`order`", nullable = false)
    private Integer order = 0;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public ReleaseDescriptor(final Release release, final Descriptor descriptor, final Integer order) {
        this.release = release;
        this.descriptor = descriptor;
        if (order != null) {
            this.order = order;
        }
    }
}
