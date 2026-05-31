package com.coderank.repository;

import com.coderank.model.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link TestCase} entities.
 * JpaRepository provides standard CRUD and pagination out of the box;
 * custom query methods below cover application-specific access patterns.
 */
public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {

    // Fetches all test cases for a problem in evaluation order.
    List<TestCase> findByProblemIdOrderByOrderIndexAsc(UUID problemId);

    // Fetches only sample test cases for display in the problem statement.
    List<TestCase> findByProblemIdAndIsSampleTrueOrderByOrderIndexAsc(UUID problemId);
}
