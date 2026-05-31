package com.coderank.judge;

import com.coderank.model.JudgeResult;
import com.coderank.model.Problem;
import com.coderank.model.Submission;
import com.coderank.model.TestCase;
import com.coderank.model.TestCaseResult;
import com.coderank.model.enums.Verdict;
import com.coderank.repository.TestCaseRepository;
import com.coderank.worker.DockerSandboxManager;
import com.coderank.worker.DockerSandboxManager.ContainerExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Central judge orchestrator that evaluates a submission against all test cases
 * for a problem. Writes the source code and test case inputs into a temp directory,
 * runs them inside the sandbox via a dedicated judge entrypoint, then parses the
 * structured output to produce per-test-case verdicts.
 *
 * <p>The judge entrypoint compiles the code once, then runs it against each test
 * case sequentially, emitting a JSON block per test case separated by a sentinel
 * delimiter. This avoids the overhead of creating a container per test case while
 * still providing fine-grained timing and verdict information.</p>
 */
@Service
public class JudgeService {

    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);
    private static final String RESULT_DELIMITER = "===CODERANK_RESULT===";

    private final DockerSandboxManager sandboxManager;
    private final TestCaseRepository testCaseRepository;
    private final ObjectMapper objectMapper;

    public JudgeService(DockerSandboxManager sandboxManager,
                        TestCaseRepository testCaseRepository,
                        ObjectMapper objectMapper) {
        this.sandboxManager = sandboxManager;
        this.testCaseRepository = testCaseRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Judges a submission against all test cases for the given problem.
     *
     * @param submission the user's code submission
     * @param problem    the problem whose test cases to evaluate against
     * @return aggregate result with per-test-case verdicts
     */
    public JudgeResult judge(Submission submission, Problem problem) {
        List<TestCase> testCases = testCaseRepository
                .findByProblemIdOrderByOrderIndexAsc(problem.getId());
        int total = testCases.size();

        if (total == 0) {
            return new JudgeResult(Verdict.ACCEPTED, 0, 0, List.of(), 0L, null);
        }

        Path tempDir = null;
        try {
            // --- Stage 1: Prepare temp directory with source code and test case inputs ---

            tempDir = Files.createTempDirectory("coderank-judge-");
            Path sourceFile = tempDir.resolve("Main.java");
            Files.writeString(sourceFile, submission.getSourceCode());

            Path testcasesDir = Files.createDirectory(tempDir.resolve("testcases"));
            for (int i = 0; i < testCases.size(); i++) {
                Path inputFile = testcasesDir.resolve(i + ".in");
                Files.writeString(inputFile, testCases.get(i).getInput());
                Files.setPosixFilePermissions(inputFile,
                        PosixFilePermissions.fromString("rw-r--r--"));
            }

            Files.writeString(tempDir.resolve("testcase_count.txt"),
                    String.valueOf(testCases.size()));

            // Rootless Docker UID remapping requires world-readable permissions.
            Files.setPosixFilePermissions(tempDir,
                    PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(sourceFile,
                    PosixFilePermissions.fromString("rw-r--r--"));
            Files.setPosixFilePermissions(testcasesDir,
                    PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(tempDir.resolve("testcase_count.txt"),
                    PosixFilePermissions.fromString("rw-r--r--"));

            // --- Stage 2: Run the judge container ---

            int perTestTimeLimit = Math.max(1,
                    (int) Math.ceil(problem.getTimeLimitMs() / 1000.0));
            long memoryBytes = (long) problem.getMemoryLimitKb() * 1024;
            int totalTimeout = perTestTimeLimit * testCases.size() + 30;

            Map<String, String> envVars = Map.of(
                    "CODERANK_TIME_LIMIT_SECONDS", String.valueOf(perTestTimeLimit)
            );

            ContainerExecution execution = sandboxManager.runContainer(
                    tempDir, "/judge-entrypoint.sh", envVars, totalTimeout, memoryBytes);

            // --- Stage 3: Parse results ---

            String stdout = execution.stdout();

            // Check for compile error
            if (stdout != null && stdout.trim().startsWith("COMPILE_ERROR")) {
                String compileError = stdout.trim().replaceFirst("COMPILE_ERROR\n?", "");
                return new JudgeResult(Verdict.COMPILATION_ERROR, 0, total,
                        List.of(), null, compileError);
            }

            // Check for container-level timeout or failure
            if (execution.exitCode() == -1) {
                return new JudgeResult(Verdict.TIME_LIMIT_EXCEEDED, 0, total,
                        List.of(), null, "Container timed out");
            }

            // Parse structured per-test-case output
            List<TestCaseResult> results = parseTestCaseResults(stdout, testCases);

            // --- Stage 4: Determine aggregate verdict ---

            Verdict overallVerdict = Verdict.ACCEPTED;
            int passed = 0;
            long totalTime = 0;

            for (TestCaseResult tcr : results) {
                if (tcr.verdict() == Verdict.ACCEPTED) {
                    passed++;
                } else if (overallVerdict == Verdict.ACCEPTED) {
                    overallVerdict = tcr.verdict();
                }
                totalTime += tcr.executionTimeMs();
            }

            return new JudgeResult(overallVerdict, passed, total, results, totalTime, null);

        } catch (Exception e) {
            log.error("Judge failed for submission {}", submission.getId(), e);
            return new JudgeResult(Verdict.RUNTIME_ERROR, 0, total,
                    List.of(), null, e.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted(Comparator.reverseOrder())
                            .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Parses the structured stdout from the judge entrypoint into individual
     * test case results. Each segment between {@code ===CODERANK_RESULT===}
     * delimiters contains a JSON object with fields: index, exitCode, stdout,
     * stderr, timeMs.
     */
    private List<TestCaseResult> parseTestCaseResults(String stdout,
                                                       List<TestCase> testCases) {
        List<TestCaseResult> results = new ArrayList<>();

        if (stdout == null || stdout.isBlank()) {
            return results;
        }

        String[] segments = stdout.split(RESULT_DELIMITER);

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;

            try {
                JsonNode node = objectMapper.readTree(trimmed);
                int index = node.get("index").asInt();
                int exitCode = node.get("exitCode").asInt();
                String testStdout = node.get("stdout").asText();
                String testStderr = node.get("stderr").asText();
                long timeMs = node.get("timeMs").asLong();

                // Determine verdict for this test case
                Verdict verdict;
                if (exitCode == 124) {
                    verdict = Verdict.TIME_LIMIT_EXCEEDED;
                } else if (exitCode != 0) {
                    verdict = Verdict.RUNTIME_ERROR;
                } else {
                    // Compare output against expected
                    String expectedOutput = (index < testCases.size())
                            ? testCases.get(index).getExpectedOutput()
                            : "";
                    verdict = OutputComparator.matches(testStdout, expectedOutput)
                            ? Verdict.ACCEPTED
                            : Verdict.WRONG_ANSWER;
                }

                boolean isSample = (index < testCases.size())
                        && testCases.get(index).isSample();

                String expectedOutput = (index < testCases.size())
                        ? testCases.get(index).getExpectedOutput()
                        : "";

                results.add(new TestCaseResult(
                        index, verdict, testStdout, expectedOutput,
                        testStderr, timeMs, isSample));

            } catch (Exception e) {
                log.warn("Failed to parse test case result segment: {}",
                        trimmed.substring(0, Math.min(200, trimmed.length())), e);
            }
        }

        return results;
    }
}
