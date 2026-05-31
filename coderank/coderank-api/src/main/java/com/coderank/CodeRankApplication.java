package com.coderank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CodeRank -- an online code execution and judging platform.
 * Users submit code, which is compiled and run inside sandboxed Docker containers,
 * then evaluated against expected outputs to produce verdicts (Accepted, Wrong Answer, etc.).
 */
@SpringBootApplication
public class CodeRankApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeRankApplication.class, args);
    }
}
