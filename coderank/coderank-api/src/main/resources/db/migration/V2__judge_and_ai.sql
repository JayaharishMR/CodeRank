-- Phase 2: Judge mode + AI assistant schema
-- Adds problem definitions, test cases, AI feedback, and links submissions
-- to problems so the judge can evaluate code against known test cases.

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

-- Difficulty tiers drive UI badges and leaderboard scoring multipliers.
CREATE TYPE difficulty AS ENUM ('EASY', 'MEDIUM', 'HARD');

-- Controls how much test-case detail the frontend reveals after a submission:
--   SHOW_FIRST_FAILING  – show input/output for the first failing test only
--   SHOW_SAMPLE_ONLY    – only ever show sample (public) tests
--   SHOW_NONE           – reveal nothing (competitive-contest mode)
CREATE TYPE test_case_visibility AS ENUM (
    'SHOW_FIRST_FAILING', 'SHOW_SAMPLE_ONLY', 'SHOW_NONE'
);

-- ============================================================================
-- PROBLEMS
-- ============================================================================

-- Each row is a self-contained coding problem.  The slug is URL-safe and unique
-- so problems can be addressed as /problems/two-sum instead of by UUID.
CREATE TABLE problems (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title                VARCHAR(255) NOT NULL,
    slug                 VARCHAR(255) NOT NULL UNIQUE,
    difficulty           difficulty NOT NULL,
    description          TEXT NOT NULL,                              -- markdown problem statement
    constraints          JSONB,                                      -- JSON array of constraint strings; JSONB enables validation and query operators
    starter_code         TEXT,
    time_limit_ms        INTEGER NOT NULL DEFAULT 2000,
    memory_limit_kb      INTEGER NOT NULL DEFAULT 262144,           -- 256 MB
    test_case_visibility test_case_visibility NOT NULL DEFAULT 'SHOW_FIRST_FAILING',
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Filter-by-difficulty queries on the problem listing page
CREATE INDEX idx_problems_difficulty ON problems(difficulty);

-- ============================================================================
-- TEST CASES
-- ============================================================================

-- Test cases are the ground truth for the judge.  Each row carries the stdin
-- that the judge pipes into the submission process and the expected stdout it
-- must produce.  Sample cases (is_sample = true) are shown in the UI; hidden
-- ones are only revealed according to the problem's test_case_visibility policy.
CREATE TABLE test_cases (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    problem_id      UUID NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
    input           TEXT NOT NULL,                                  -- stdin for this test case
    expected_output TEXT NOT NULL,                                  -- expected stdout
    is_sample       BOOLEAN NOT NULL DEFAULT false,
    order_index     INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_test_cases_problem_id ON test_cases(problem_id);

-- Within a single problem, order_index must be unique so the judge can run
-- tests in a deterministic, repeatable sequence.
ALTER TABLE test_cases ADD CONSTRAINT uq_test_cases_problem_order
    UNIQUE (problem_id, order_index);

-- ============================================================================
-- AI FEEDBACKS
-- ============================================================================

-- Stores AI-generated feedback for a submission.  Kept in its own table rather
-- than a column on submissions so we can track token usage, swap models, and
-- regenerate feedback without touching the submission row.
CREATE TABLE ai_feedbacks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id     UUID NOT NULL REFERENCES submissions(id),
    feedback          TEXT NOT NULL,
    model             VARCHAR(100),                                 -- e.g. 'gpt-4o', 'claude-3-opus'
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_feedbacks_submission_id ON ai_feedbacks(submission_id);

-- ============================================================================
-- ALTER SUBMISSIONS — link to problems
-- ============================================================================

-- problem_id is nullable: playground (Phase 1) submissions have no associated
-- problem; judge-mode submissions always do.  ON DELETE SET NULL preserves
-- submission history when a problem is deleted — the submission becomes a
-- "detached" playground-like submission.
ALTER TABLE submissions ADD COLUMN problem_id UUID REFERENCES problems(id) ON DELETE SET NULL;

-- Track per-submission test-case progress so the UI can show "5 / 8 passed"
ALTER TABLE submissions ADD COLUMN passed_test_cases INTEGER;
ALTER TABLE submissions ADD COLUMN total_test_cases  INTEGER;

CREATE INDEX idx_submissions_problem_id ON submissions(problem_id);

-- ============================================================================
-- SEED DATA — starter problems
-- ============================================================================

-- Use CTEs to capture the generated UUIDs so test_cases can reference them
-- without hard-coding IDs.

-- Problem 1: Two Sum
WITH inserted_problem AS (
    INSERT INTO problems (title, slug, difficulty, description, constraints, starter_code, time_limit_ms, memory_limit_kb)
    VALUES (
        'Two Sum',
        'two-sum',
        'EASY',
        E'Given an array of integers `nums` and an integer `target`, return the indices of the two numbers that add up to `target`.\n\nYou may assume that each input would have exactly one solution, and you may not use the same element twice.',
        '["2 <= nums.length <= 10^4", "-10^9 <= nums[i] <= 10^9", "Only one valid answer exists."]',
        E'import java.util.*;\n\npublic class Main {\n    public static int[] twoSum(int[] nums, int target) {\n        // Your code here\n        return new int[]{};\n    }\n\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int n = sc.nextInt();\n        int[] nums = new int[n];\n        for (int i = 0; i < n; i++) nums[i] = sc.nextInt();\n        int target = sc.nextInt();\n        int[] result = twoSum(nums, target);\n        System.out.println(Arrays.toString(result));\n    }\n}',
        2000,
        262144
    )
    RETURNING id
)
INSERT INTO test_cases (problem_id, input, expected_output, is_sample, order_index) VALUES
    ((SELECT id FROM inserted_problem), E'4\n2 7 11 15\n9',          '[0, 1]', true,  0),
    ((SELECT id FROM inserted_problem), E'3\n3 2 4\n6',              '[1, 2]', true,  1),
    ((SELECT id FROM inserted_problem), E'2\n0 0\n0',                '[0, 1]', false, 2),
    ((SELECT id FROM inserted_problem), E'4\n-1 -2 -3 -4\n-6',      '[1, 3]', false, 3),
    ((SELECT id FROM inserted_problem), E'5\n1 5 3 7 2\n9',          '[3, 4]', false, 4);

-- Problem 2: FizzBuzz
WITH inserted_problem AS (
    INSERT INTO problems (title, slug, difficulty, description, constraints, starter_code, time_limit_ms, memory_limit_kb)
    VALUES (
        'FizzBuzz',
        'fizzbuzz',
        'EASY',
        E'Given an integer `n`, print numbers from 1 to n. But for multiples of 3 print "Fizz", for multiples of 5 print "Buzz", and for multiples of both 3 and 5 print "FizzBuzz".',
        '["1 <= n <= 10^4"]',
        E'import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int n = sc.nextInt();\n        // Your code here\n    }\n}',
        2000,
        262144
    )
    RETURNING id
)
INSERT INTO test_cases (problem_id, input, expected_output, is_sample, order_index) VALUES
    ((SELECT id FROM inserted_problem), '5',  E'1\n2\nFizz\n4\nBuzz', true,  0),
    ((SELECT id FROM inserted_problem), '15', E'1\n2\nFizz\n4\nBuzz\nFizz\n7\n8\nFizz\nBuzz\n11\nFizz\n13\n14\nFizzBuzz', true,  1),
    ((SELECT id FROM inserted_problem), '1',  '1',                    false, 2),
    ((SELECT id FROM inserted_problem), '3',  E'1\n2\nFizz',          false, 3);

-- Problem 3: Palindrome Check
WITH inserted_problem AS (
    INSERT INTO problems (title, slug, difficulty, description, constraints, starter_code, time_limit_ms, memory_limit_kb)
    VALUES (
        'Palindrome Check',
        'palindrome-check',
        'EASY',
        E'Given a string `s`, determine if it is a palindrome. Consider only alphanumeric characters and ignore cases.',
        '["1 <= s.length <= 2 * 10^5", "s consists only of printable ASCII characters."]',
        E'import java.util.Scanner;\n\npublic class Main {\n    public static boolean isPalindrome(String s) {\n        // Your code here\n        return false;\n    }\n\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        String s = sc.nextLine();\n        System.out.println(isPalindrome(s));\n    }\n}',
        2000,
        262144
    )
    RETURNING id
)
INSERT INTO test_cases (problem_id, input, expected_output, is_sample, order_index) VALUES
    ((SELECT id FROM inserted_problem), 'A man, a plan, a canal: Panama', 'true',  true,  0),
    ((SELECT id FROM inserted_problem), 'race a car',                      'false', true,  1),
    ((SELECT id FROM inserted_problem), ' ',                               'true',  false, 2),
    ((SELECT id FROM inserted_problem), 'a',                               'true',  false, 3);

-- Problem 4: Reverse Linked List
WITH inserted_problem AS (
    INSERT INTO problems (title, slug, difficulty, description, constraints, starter_code, time_limit_ms, memory_limit_kb)
    VALUES (
        'Reverse Linked List',
        'reverse-linked-list',
        'MEDIUM',
        E'Given the head of a singly linked list, reverse the list and return the reversed list. Read space-separated integers from stdin, output the reversed list as space-separated integers.',
        '["The number of nodes in the list is in the range [0, 5000].", "-5000 <= Node.val <= 5000"]',
        E'import java.util.*;\n\npublic class Main {\n    static class ListNode {\n        int val;\n        ListNode next;\n        ListNode(int val) { this.val = val; }\n    }\n\n    public static ListNode reverseList(ListNode head) {\n        // Your code here\n        return null;\n    }\n\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        String line = sc.nextLine().trim();\n        if (line.isEmpty()) {\n            System.out.println();\n            return;\n        }\n        String[] parts = line.split(" ");\n        ListNode dummy = new ListNode(0);\n        ListNode curr = dummy;\n        for (String p : parts) {\n            curr.next = new ListNode(Integer.parseInt(p));\n            curr = curr.next;\n        }\n        ListNode reversed = reverseList(dummy.next);\n        StringBuilder sb = new StringBuilder();\n        while (reversed != null) {\n            if (sb.length() > 0) sb.append(" ");\n            sb.append(reversed.val);\n            reversed = reversed.next;\n        }\n        System.out.println(sb.toString());\n    }\n}',
        3000,
        262144
    )
    RETURNING id
)
INSERT INTO test_cases (problem_id, input, expected_output, is_sample, order_index) VALUES
    ((SELECT id FROM inserted_problem), '1 2 3 4 5', '5 4 3 2 1', true,  0),
    ((SELECT id FROM inserted_problem), '1 2',       '2 1',       true,  1),
    ((SELECT id FROM inserted_problem), '42',        '42',        false, 2),
    ((SELECT id FROM inserted_problem), '',          '',           false, 3);
