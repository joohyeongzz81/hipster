package com.hipster.artist.repository;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.domain.ArtistStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArtistRepository extends JpaRepository<Artist, Long> {

    Page<Artist> findByNameContainingIgnoreCaseAndStatus(String name, ArtistStatus status, Pageable pageable);

    Optional<Artist> findByIdAndStatusNot(Long id, ArtistStatus status);
}
