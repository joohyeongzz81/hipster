package com.hipster.moderation.repository;

import com.hipster.moderation.domain.ModerationAuditTrail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ModerationAuditTrailRepository extends JpaRepository<ModerationAuditTrail, Long> {
}
