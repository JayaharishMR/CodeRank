package com.coderank.repository;

import com.coderank.model.Submission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Submission} entities.
 * JpaRepository provides standard CRUD and pagination out of the box;
 * custom query methods below cover application-specific access patterns.
 */
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    // Spring Data derives the query from the method name:
    // filters by userId and returns results newest-first so the user's
    // submission history page shows recent submissions at the top.
    Page<Submission> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Submission> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT s FROM Submission s LEFT JOIN FETCH s.problem WHERE s.id = :id")
    Optional<Submission> findByIdWithProblem(UUID id);
}
