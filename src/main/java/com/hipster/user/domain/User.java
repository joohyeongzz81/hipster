package com.hipster.user.domain;

import com.hipster.auth.UserRole;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users", indexes = {
        @Index(name = "idx_users_username", columnList = "username"),
        @Index(name = "idx_users_email", columnList = "email"),
        @Index(name = "idx_users_last_active", columnList = "lastActiveDate")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false)
    @ColumnDefault("0.0")
    private Double weightingScore = 0.0;

    @Column(nullable = false)
    @ColumnDefault("false")
    private Boolean reviewBonus = false;

    @Column(nullable = false)
    private LocalDateTime lastActiveDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserRole moderationRole;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public User(final String username, final String email, final String passwordHash) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.lastActiveDate = LocalDateTime.now();
    }

    // == Business Methods == //

    public void updateLastActiveDate() {
        this.lastActiveDate = LocalDateTime.now();
    }

    public void updateWeightingScore(final Double score) {
        if (score >= 0.0 && score <= 1.25) {
            this.weightingScore = score;
        }
    }

    public boolean hasRole(final UserRole role) {
        return this.moderationRole != null && this.moderationRole == role;
    }

    @PrePersist
    protected void onPrePersist() {
        if (this.weightingScore == null) {
            this.weightingScore = 0.0;
        }
        if (this.reviewBonus == null) {
            this.reviewBonus = false;
        }
        if (this.lastActiveDate == null) {
            this.lastActiveDate = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
