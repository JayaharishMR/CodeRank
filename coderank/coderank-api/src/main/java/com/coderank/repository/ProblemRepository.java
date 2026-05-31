package com.coderank.repository;

import com.coderank.model.Problem;
import com.coderank.model.enums.Difficulty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Problem} entities.
 * JpaRepository provides standard CRUD and pagination out of the box;
 * custom query methods below cover application-specific access patterns.
 */
public interface ProblemRepository extends JpaRepository<Problem, UUID> {

    // Slug-based lookup for resolving human-readable URLs to problem entities.
    Optional<Problem> findBySlug(String slug);

    // Default listing returns all problems newest-first for the browse page.
    Page<Problem> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // Filtered listing by difficulty, also newest-first, for difficulty tabs.
    Page<Problem> findByDifficultyOrderByCreatedAtDesc(Difficulty difficulty, Pageable pageable);
}
