package com.hipster.user.repository;

import com.hipster.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.lastActiveDate = :now WHERE u.id = :userId")
    void updateLastActiveDate(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
