package com.coderank.repository;

import com.coderank.model.AiFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link AiFeedback} entities.
 * JpaRepository provides standard CRUD and pagination out of the box;
 * custom query methods below cover application-specific access patterns.
 */
public interface AiFeedbackRepository extends JpaRepository<AiFeedback, UUID> {

    // Looks up the AI feedback associated with a specific submission.
    // Returns Optional since not every submission will have AI feedback.
    Optional<AiFeedback> findBySubmissionId(UUID submissionId);
}
