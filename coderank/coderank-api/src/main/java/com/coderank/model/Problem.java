package com.coderank.model;

import com.coderank.model.enums.Difficulty;
import com.coderank.model.enums.TestCaseVisibility;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a coding problem with its metadata, constraints, and associated
 * test cases. Each problem defines time/memory limits that the sandbox enforces
 * when evaluating submissions against its test cases.
 */
@Entity
@Table(name = "problems")
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "title", nullable = false)
    private String title;

    // URL-safe identifier for human-readable problem URLs (e.g. "two-sum").
    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false)
    private Difficulty difficulty;

    @Column(name = "description", columnDefinition = "TEXT", nullable = false)
    private String description;

    // Stored as JSONB to allow flexible, queryable constraint definitions
    // (e.g. input size bounds, value ranges) without a rigid column schema.
    @Column(name = "constraints", columnDefinition = "JSONB")
    private String constraints;

    @Column(name = "starter_code", columnDefinition = "TEXT")
    private String starterCode;

    // Default of 2000ms (2 seconds) matches common competitive programming
    // time limits and is generous enough for most interpreted languages.
    @Column(name = "time_limit_ms", nullable = false)
    private int timeLimitMs = 2000;

    // Default of 256 MB (262144 KB) provides ample memory for most solutions
    // while preventing runaway allocations from crashing the sandbox host.
    @Column(name = "memory_limit_kb", nullable = false)
    private int memoryLimitKb = 262144;

    @Enumerated(EnumType.STRING)
    @Column(name = "test_case_visibility", nullable = false)
    private TestCaseVisibility testCaseVisibility = TestCaseVisibility.SHOW_FIRST_FAILING;

    // CascadeType.ALL propagates persist/merge/remove to test cases so they
    // are managed as part of the problem aggregate. LAZY avoids loading all
    // test cases when only problem metadata is needed.
    @OneToMany(mappedBy = "problem", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TestCase> testCases = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getConstraints() {
        return constraints;
    }

    public void setConstraints(String constraints) {
        this.constraints = constraints;
    }

    public String getStarterCode() {
        return starterCode;
    }

    public void setStarterCode(String starterCode) {
        this.starterCode = starterCode;
    }

    public int getTimeLimitMs() {
        return timeLimitMs;
    }

    public void setTimeLimitMs(int timeLimitMs) {
        this.timeLimitMs = timeLimitMs;
    }

    public int getMemoryLimitKb() {
        return memoryLimitKb;
    }

    public void setMemoryLimitKb(int memoryLimitKb) {
        this.memoryLimitKb = memoryLimitKb;
    }

    public TestCaseVisibility getTestCaseVisibility() {
        return testCaseVisibility;
    }

    public void setTestCaseVisibility(TestCaseVisibility testCaseVisibility) {
        this.testCaseVisibility = testCaseVisibility;
    }

    public List<TestCase> getTestCases() {
        return testCases;
    }

    public void setTestCases(List<TestCase> testCases) {
        this.testCases = testCases;
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
