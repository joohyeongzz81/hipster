package com.hipster.release.repository;

import com.hipster.release.domain.ReleaseGenre;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseGenreRepository extends JpaRepository<ReleaseGenre, Long> {
}
