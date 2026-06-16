package com.coderank.controller;

import com.coderank.dto.ProblemResponse;
import com.coderank.dto.TestCaseDto;
import com.coderank.model.Problem;
import com.coderank.service.ProblemService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Read-only API for browsing coding problems. Supports listing all problems
 * and fetching a single problem by either UUID or slug. Sample test cases
 * are included in the response; hidden test cases are never exposed here.
 */
@RestController
@RequestMapping("/api/v1/problems")
public class ProblemController {

    private final ProblemService problemService;
    private final ObjectMapper objectMapper;

    public ProblemController(ProblemService problemService, ObjectMapper objectMapper) {
        this.problemService = problemService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<ProblemResponse> listProblems() {
        return problemService.getAllProblems().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{idOrSlug}")
    public ProblemResponse getProblem(@PathVariable String idOrSlug) {
        Problem problem;
        try {
            UUID id = UUID.fromString(idOrSlug);
            problem = problemService.getById(id);
        } catch (IllegalArgumentException e) {
            // Not a UUID, try slug
            problem = problemService.getBySlug(idOrSlug);
        }
        return toResponse(problem);
    }

    private ProblemResponse toResponse(Problem p) {
        // Parse constraints JSONB string to List<String>
        List<String> constraints = List.of();
        if (p.getConstraints() != null && !p.getConstraints().isBlank()) {
            try {
                constraints = objectMapper.readValue(p.getConstraints(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                // fallback to empty list
            }
        }

        // Fetch only sample test cases
        List<TestCaseDto> sampleTestCases = problemService.getSampleTestCases(p.getId()).stream()
                .map(tc -> new TestCaseDto(tc.getInput(), tc.getExpectedOutput()))
                .toList();

        return new ProblemResponse(
                p.getId(), p.getTitle(), p.getSlug(),
                p.getDifficulty().name(), p.getDescription(),
                constraints, p.getStarterCode(),
                p.getTimeLimitMs(), p.getMemoryLimitKb(),
                sampleTestCases
        );
    }
}
