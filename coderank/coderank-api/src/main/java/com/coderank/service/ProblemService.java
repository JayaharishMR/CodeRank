package com.coderank.service;

import com.coderank.model.Problem;
import com.coderank.model.TestCase;
import com.coderank.repository.ProblemRepository;
import com.coderank.repository.TestCaseRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Business logic layer for problem retrieval. Supports lookup by ID or slug
 * so the API can accept either format in the URL path. Sample test cases are
 * fetched separately to avoid loading hidden test cases into the response.
 */
@Service
public class ProblemService {

    private final ProblemRepository problemRepository;
    private final TestCaseRepository testCaseRepository;

    public ProblemService(ProblemRepository problemRepository,
                          TestCaseRepository testCaseRepository) {
        this.problemRepository = problemRepository;
        this.testCaseRepository = testCaseRepository;
    }

    public List<Problem> getAllProblems() {
        return problemRepository.findAll();
    }

    public Problem getById(UUID id) {
        return problemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Problem not found: " + id));
    }

    public Problem getBySlug(String slug) {
        return problemRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Problem not found: " + slug));
    }

    public List<TestCase> getSampleTestCases(UUID problemId) {
        return testCaseRepository.findByProblemIdAndIsSampleTrueOrderByOrderIndexAsc(problemId);
    }
}
