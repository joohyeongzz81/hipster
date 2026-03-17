package com.hipster.reward.repository;

import com.hipster.reward.domain.RewardCampaignParticipation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RewardCampaignParticipationRepository extends JpaRepository<RewardCampaignParticipation, Long> {

    Optional<RewardCampaignParticipation> findByCampaignCodeAndUserId(String campaignCode, Long userId);

    boolean existsByCampaignCodeAndUserIdAndActiveTrue(String campaignCode, Long userId);
}
