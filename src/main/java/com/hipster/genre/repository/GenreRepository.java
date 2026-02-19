package com.hipster.genre.repository;

import com.hipster.genre.domain.Genre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GenreRepository extends JpaRepository<Genre, Long> {

    List<Genre> findByParentId(Long parentId);

    List<Genre> findAllByPendingApprovalFalse();
}
