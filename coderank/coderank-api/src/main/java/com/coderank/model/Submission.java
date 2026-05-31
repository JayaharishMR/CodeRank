package com.coderank.model;

import com.coderank.model.enums.Language;
import com.coderank.model.enums.SubmissionStatus;
import com.coderank.model.enums.Verdict;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single code submission for execution in the sandbox.
 * Tracks the full lifecycle from submission through compilation, execution,
 * and final verdict. Supports both authenticated and guest (anonymous) users.
 */
@Entity
@Table(name = "submissions")
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Nullable to allow guest submissions without requiring authentication.
    // When null, the submission is treated as anonymous.
    @Column(name = "user_id")
    private UUID userId;

    // EnumType.STRING stores the enum name ("JAVA") instead of ordinal (0),
    // so adding/reordering enum values won't silently corrupt existing data.
    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false)
    private Language language = Language.JAVA;

    @Column(name = "source_code", columnDefinition = "TEXT", nullable = false)
    private String sourceCode;

    @Column(name = "stdin", columnDefinition = "TEXT")
    private String stdin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubmissionStatus status = SubmissionStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false)
    private Verdict verdict = Verdict.PENDING;

    // Populated after successful execution; null while still processing.
    @Column(name = "stdout", columnDefinition = "TEXT")
    private String stdout;

    @Column(name = "stderr", columnDefinition = "TEXT")
    private String stderr;

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    // Nullable because memory tracking may not be available for all execution
    // environments or may fail to report for crashed processes.
    @Column(name = "memory_used_kb")
    private Long memoryUsedKb;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // Nullable — when null, this submission is a playground (free-run) execution
    // rather than a problem-based evaluation.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @Column(name = "passed_test_cases")
    private Integer passedTestCases;

    @Column(name = "total_test_cases")
    private Integer totalTestCases;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // JPA lifecycle callbacks manage timestamps automatically so callers
    // don't need to set them manually and they stay consistent.
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // @PreUpdate fires on every persist after the first, keeping updatedAt
    // in sync with status/verdict transitions without explicit caller action.
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public Language getLanguage() {
        return language;
    }

    public void setLanguage(Language language) {
        this.language = language;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getStdin() {
        return stdin;
    }

    public void setStdin(String stdin) {
        this.stdin = stdin;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public void setStatus(SubmissionStatus status) {
        this.status = status;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public void setVerdict(Verdict verdict) {
        this.verdict = verdict;
    }

    public String getStdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    public Long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(Long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public Long getMemoryUsedKb() {
        return memoryUsedKb;
    }

    public void setMemoryUsedKb(Long memoryUsedKb) {
        this.memoryUsedKb = memoryUsedKb;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Problem getProblem() {
        return problem;
    }

    public void setProblem(Problem problem) {
        this.problem = problem;
    }

    public Integer getPassedTestCases() {
        return passedTestCases;
    }

    public void setPassedTestCases(Integer passedTestCases) {
        this.passedTestCases = passedTestCases;
    }

    public Integer getTotalTestCases() {
        return totalTestCases;
    }

    public void setTotalTestCases(Integer totalTestCases) {
        this.totalTestCases = totalTestCases;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
