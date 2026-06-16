# CodeRank FAQ — Architecture, Data Flow & Design Decisions

This document explains how CodeRank works under the hood, why specific architectural choices were made, and how data flows through the system. It is intended for developers, reviewers, or anyone wanting to understand the project deeply.

---

## Table of Contents

- [General Architecture](#general-architecture)
- [Code Execution Pipeline](#code-execution-pipeline)
- [Docker Sandboxing & Security](#docker-sandboxing--security)
- [Judge Mode](#judge-mode)
- [AI Feedback](#ai-feedback)
- [Database Design](#database-design)
- [Frontend Architecture](#frontend-architecture)
- [WebSocket & Real-Time Updates](#websocket--real-time-updates)
- [Authentication & Rate Limiting](#authentication--rate-limiting)
- [Extension System (SPI)](#extension-system-spi)
- [Miscellaneous](#miscellaneous)

---

## General Architecture

### Q: What is CodeRank?

CodeRank is an online code execution and judging platform, similar to LeetCode or Codeforces. Users write code in a browser-based Monaco editor, submit it, and get real-time feedback. It operates in two modes:

1. **Playground mode** — free-form execution with optional stdin input. Code runs and you see stdout/stderr.
2. **Judge mode** — code is evaluated against hidden test cases for a specific problem. You get a verdict (Accepted, Wrong Answer, TLE, etc.) and per-test-case results.

An optional **AI feedback** feature provides on-demand code review powered by OpenAI or any compatible API.

### Q: What is the high-level architecture?

```
User Browser (React UI)
    ↓ REST + WebSocket
Spring Boot API (port 8080)
    ↓ Enqueue submission ID
RabbitMQ (message broker)
    ↓ Dequeue
Execution Worker
    ↓ Creates container
Docker Sandbox (ephemeral, hardened container)
    ↓ Results back
Worker persists to PostgreSQL + pushes via WebSocket
    ↓
User sees real-time status updates
```

The key principle is **asynchronous processing**: the API immediately returns `202 Accepted` after saving the submission and enqueuing it. The actual compilation and execution happen in a background worker. The user sees live updates via WebSocket.

### Q: Why separate the API from the worker?

The API handles HTTP requests, JWT validation, rate limiting, and WebSocket management. The worker handles Docker container orchestration, which is slow (1-10 seconds per submission). If the API blocked on execution, it couldn't handle concurrent requests.

By decoupling via RabbitMQ:
- The API stays responsive even under load
- Workers can be scaled independently (run multiple workers on separate machines)
- If a worker crashes, queued submissions aren't lost — RabbitMQ redelivers them
- The API and worker can be restarted independently without affecting each other

### Q: Why RabbitMQ and not a simpler queue?

We considered several options:

| Option | Why not |
|--------|---------|
| In-memory queue (BlockingQueue) | Lost on restart, single-node only |
| Redis queue | Redis is already used for rate limiting, but lacks delivery guarantees |
| Database polling | Adds latency, wastes DB connections with repeated queries |
| **RabbitMQ** | Persistent, delivery guarantees, dead-letter support, battle-tested |

RabbitMQ gives us message acknowledgment (submissions aren't lost), fan-out capability (multiple workers), and a management UI for debugging queue state during development.

### Q: What does the message payload look like?

Just the submission UUID as a string. Example: `"a1b2c3d4-e5f6-7890-abcd-ef1234567890"`.

The worker fetches the full submission entity from PostgreSQL after dequeuing. This keeps messages small, avoids serialization coupling, and ensures the worker always reads the latest state.

---

## Code Execution Pipeline

### Q: What happens when a user clicks "Submit"?

Here is the complete lifecycle for a playground submission:

```
1. [Frontend] User clicks Submit
2. [Frontend] POST /api/v1/submissions with {language, sourceCode, stdin}
3. [API] SubmissionService.submit():
   a. Creates Submission entity (status: QUEUED, verdict: PENDING)
   b. Persists to PostgreSQL
   c. Enqueues submission ID to RabbitMQ
   d. Returns 202 Accepted with submission details + wsChannel
4. [Frontend] Subscribes to WebSocket topic /topic/submissions/{id}
5. [Worker] ExecutionWorker.processSubmission():
   a. Dequeues submission ID from RabbitMQ
   b. Fetches submission from PostgreSQL (with JOIN FETCH for problem)
   c. Routes to playground or judge mode based on problemId
6. [Worker] Playground mode:
   a. Sets status to COMPILING → saves + pushes via WebSocket
   b. Sets status to RUNNING → saves + pushes via WebSocket
   c. DockerSandboxManager.execute():
      i.   Creates temp directory, writes Main.java and optional stdin.txt
      ii.  Creates Docker container with security config
      iii. Starts container, waits for completion (with timeout)
      iv.  Captures stdout/stderr from container logs
      v.   Removes container
      vi.  Cleans up temp directory
   d. Sets status to COMPLETED, verdict to ACCEPTED/COMPILATION_ERROR/etc.
   e. Saves final state to PostgreSQL
   f. Pushes final result via WebSocket
7. [Frontend] Receives WebSocket messages, updates UI in real-time
```

### Q: How does the sandbox compile and run the code?

The sandbox uses `entrypoint.sh` (playground) or `judge-entrypoint.sh` (judge mode). For playground:

1. **Compile:** `javac -d /tmp/out /workspace/*.java` — compiles all Java files. Stderr is captured to a file. If compilation fails, outputs `COMPILE_ERROR` + the error text and exits.
2. **Detect main class:** Greps source files for `public static void main` to find the entry point class name.
3. **Run:** `java -cp /tmp/out $MAIN_CLASS` — if `stdin.txt` exists, it's piped as input.

The `/workspace` directory is bind-mounted from a temp directory on the host that contains the user's source code.

### Q: What verdicts are possible?

| Verdict | Meaning | How detected |
|---------|---------|--------------|
| `ACCEPTED` | Code ran successfully (playground) or all test cases passed (judge) | Exit code 0 + output matches expected |
| `WRONG_ANSWER` | Output doesn't match expected (judge mode only) | OutputComparator returns false |
| `COMPILATION_ERROR` | javac failed | Container stdout starts with `COMPILE_ERROR` |
| `RUNTIME_ERROR` | Code crashed (NPE, ArrayIndexOutOfBounds, etc.) | Non-zero exit code (not 124) |
| `TIME_LIMIT_EXCEEDED` | Code exceeded time limit | Exit code 124 from `timeout` command, or container-level timeout |
| `MEMORY_LIMIT_EXCEEDED` | Code exceeded memory limit | Docker OOM kill (exit code 137) |
| `PENDING` | Not yet processed | Initial state before worker picks it up |

---

## Docker Sandboxing & Security

### Q: Why Docker containers for sandboxing?

User-submitted code is untrusted. It could attempt to:
- Read sensitive files on the host
- Make network requests (exfiltrate data, DDoS)
- Fork-bomb the host
- Consume all available memory
- Run indefinitely

Docker provides OS-level isolation — each submission runs in its own container with strict resource limits. Containers are ephemeral: created before execution, destroyed after.

Alternatives considered:

| Option | Why not |
|--------|---------|
| JVM SecurityManager | Deprecated in Java 17, removed in Java 24. Unreliable sandboxing. |
| Separate VM per execution | Too slow (boot time), too expensive (resource overhead) |
| nsjail / bubblewrap | Powerful but complex to configure, less portable |
| **Docker** | Well-understood, portable, rich API (docker-java), fine-grained resource controls |

### Q: What are the 6 security layers?

Every sandbox container has multiple overlapping security measures:

**Layer 1 — Container Isolation:**
Each submission gets its own container. No shared filesystem, no shared processes, no cross-submission interference. The container is destroyed after execution.

**Layer 2 — Network Disabled:**
`--network none` on the container. Zero network access. User code cannot:
- Make HTTP requests
- Connect to databases
- Exfiltrate data
- Download dependencies
- Participate in DDoS attacks

**Layer 3 — Resource Limits:**
- `--memory 256m` — prevents OOM-killing the host
- `--cpus 1` — prevents CPU starvation of other processes
- `--pids-limit 50` — prevents fork bombs (`while(true) new Thread()`)

**Layer 4 — Execution Timeout:**
Two levels of timeout:
- `timeout` command inside the container (per-test in judge mode)
- Docker API `WaitContainerResultCallback` with hard timeout (container is force-killed)

**Layer 5 — Capability Drop + No-New-Privileges:**
- `--cap-drop ALL` — drops all Linux capabilities (no `CAP_NET_RAW`, no `CAP_SYS_ADMIN`, etc.)
- `--security-opt no-new-privileges` — blocks setuid/setgid escalation

**Layer 6 — Image Hardening:**
- Non-root user (`coderank`) with no login shell (`/bin/false`)
- `wget` and `curl` removed from the image
- Only bash + JDK installed — minimal attack surface

### Q: Why use a tmpfs for compiled class files?

The `--tmpfs /tmp:size=50m` flag creates an in-memory filesystem at `/tmp` inside the container. Benefits:
- Compiled `.class` files never touch real disk
- Automatically cleaned up when the container exits
- Faster than disk I/O
- Size-limited (50 MB) — prevents user code from filling up disk

### Q: How is the source code mounted into the container?

The worker creates a temp directory on the host, writes the user's `Main.java` (and test case inputs for judge mode) into it, then bind-mounts it to `/workspace` inside the container:

```
Host: /tmp/coderank-xyz/Main.java  →  Container: /workspace/Main.java
```

File permissions are set to world-readable (`rw-r--r--`) because Docker's UID remapping may cause the container's `coderank` user to have a different UID than the host user.

---

## Judge Mode

### Q: How does judge mode differ from playground mode?

| Aspect | Playground | Judge |
|--------|-----------|-------|
| Input source | User-provided stdin | Test cases from database |
| Verdict determination | Always ACCEPTED if code runs | Compared against expected output |
| Container script | `/entrypoint.sh` | `/judge-entrypoint.sh` |
| Test cases | None | Multiple (sample + hidden) |
| Output to user | Raw stdout/stderr | Per-test-case verdicts + pass/fail summary |

### Q: Why "compile once, run N times" instead of one container per test case?

We considered three approaches:

| Approach | Pros | Cons |
|----------|------|------|
| Container per test case | Clean isolation between tests | Massive overhead — container create/start/stop takes 1-3 seconds each. 10 test cases = 10-30 seconds of just Docker overhead |
| Compile once, run N in single container | One container overhead, one compilation | Slightly less isolation between test runs (shared memory space) |
| Pre-compiled, run N | Even faster | Security risk — someone could tamper with compiled classes |

We chose **compile once, run N** because:
- Docker container lifecycle is the dominant cost. A single container creation (~1-2s) vs 10 (~10-20s) is a massive difference.
- Compilation only needs to happen once — the source code doesn't change between test cases.
- Each `java` invocation within the container still gets a fresh JVM process, so test cases can't interfere with each other via static state.

### Q: How does the judge entrypoint script work?

`judge-entrypoint.sh` runs inside the container:

```
Step 1: mkdir /tmp/out
Step 2: javac -d /tmp/out /workspace/*.java  (compile once)
Step 3: Detect main class via grep
Step 4: Read test case count from /workspace/testcase_count.txt
Step 5: Configure per-test timeout from $CODERANK_TIME_LIMIT_SECONDS
Step 6: For each test case i=0..N-1:
  a. Record start time (nanoseconds)
  b. timeout $TIME_LIMIT java -cp /tmp/out $MAIN_CLASS < /workspace/testcases/$i.in
  c. Capture stdout → /tmp/stdout_$i.txt, stderr → /tmp/stderr_$i.txt
  d. Record end time, compute elapsed milliseconds
  e. Escape stdout/stderr for JSON
  f. Print sentinel: ===CODERANK_RESULT===
  g. Print JSON: {"index":0,"exitCode":0,"stdout":"...","stderr":"...","timeMs":123}
Step 7: Exit 0 (individual failures are in the JSON)
```

### Q: Why use a sentinel delimiter (`===CODERANK_RESULT===`) instead of newline-delimited JSON?

User code can print arbitrary text to stdout. If we just used newline-delimited JSON, a user's `System.out.println(...)` would corrupt the parsing. The sentinel is a unique string that user code is extremely unlikely to output, making parsing reliable even when user output contains JSON, newlines, or other special characters.

### Q: How is output comparison done?

The `OutputComparator` class does exact-match comparison with minor normalization:

1. Split both actual and expected output into lines
2. Strip trailing whitespace from each line (spaces, tabs)
3. Strip trailing empty lines
4. Compare the normalized strings

This means:
- `"Hello\n"` matches `"Hello"` (trailing newline stripped)
- `"Hello  "` matches `"Hello"` (trailing spaces stripped)
- `"Hello"` does NOT match `"hello"` (case-sensitive)
- `" Hello"` does NOT match `"Hello"` (leading whitespace preserved)

### Q: Why exact match and not custom checkers?

Custom checkers (like Codeforces uses) allow problem-specific comparison logic (e.g., "any valid permutation is accepted"). But they add complexity:
- Need a checker language (usually C++ or Python)
- Need to sandbox the checker too
- More attack surface

Exact match is simple, predictable, and sufficient for most problems. It's how LeetCode handles most problems. The tradeoff is that problem setters must specify a single canonical output format.

### Q: What is test case visibility?

Each problem has a `test_case_visibility` setting that controls how much test case information the user sees:

| Setting | Sample test cases | Hidden test cases (passing) | Hidden test cases (failing) |
|---------|-------------------|----------------------------|----------------------------|
| `SHOW_FIRST_FAILING` | Full details (input, expected, actual) | Verdict + time only | First failing: full details. Rest: verdict + time only |
| `SHOW_SAMPLE_ONLY` | Full details | Verdict + time only | Verdict + time only |
| `SHOW_NONE` | Verdict + time only | Verdict + time only | Verdict + time only |

This prevents users from reverse-engineering hidden test cases by examining the "expected output" field. The default is `SHOW_FIRST_FAILING`, which shows enough info for debugging (the first failing case) without revealing the full test suite.

### Q: How are per-problem time/memory limits enforced?

Each problem specifies:
- `time_limit_ms` (default: 2000ms) — converted to seconds and passed as `$CODERANK_TIME_LIMIT_SECONDS` env var to the container. The `timeout` command enforces this per test case.
- `memory_limit_kb` (default: 262144 = 256 MB) — passed as `--memory` flag to Docker at container creation. Docker's cgroup enforcement kills the process if it exceeds this.

The total container timeout is calculated as: `(per_test_time_limit * test_case_count) + 30s buffer`. The 30-second buffer accounts for JVM startup, compilation time, and any overhead between test case runs.

---

## AI Feedback

### Q: How does AI feedback work?

The flow:

```
1. User clicks "Get AI Feedback" on a completed submission
2. Frontend POSTs to /api/v1/submissions/{id}/feedback
3. API returns 202 Accepted immediately (non-blocking)
4. AiFeedbackService runs asynchronously (@Async):
   a. Fetches submission from DB (with problem details)
   b. Checks idempotency — if feedback already exists, re-sends via WebSocket
   c. Builds a prompt with: source code, verdict, execution time, problem description, test case results, stderr
   d. Calls OpenAI chat completions API (POST /chat/completions)
   e. Parses response, extracts feedback text + token usage
   f. Persists to ai_feedbacks table (for future retrieval)
   g. Pushes feedback via WebSocket to /topic/submissions/{id}
5. Frontend receives WebSocket message, renders as markdown
```

### Q: Why on-demand instead of automatic?

We considered triggering AI feedback automatically on every submission. Reasons against:

- **Cost:** AI API calls cost money. With automatic triggering, every test submission, syntax error fix, and partial solution would incur a charge. On-demand lets users choose when they want feedback.
- **Noise:** Automatic feedback on incomplete or work-in-progress code would be annoying and unhelpful.
- **User control:** Users should decide when they want AI assistance. This aligns with the educational goal — the user should think first, then optionally ask for help.

### Q: Why async instead of synchronous?

AI API calls take 3-15 seconds. A synchronous endpoint would block the HTTP thread and the user's browser for that entire duration. Async gives us:

- `202 Accepted` returns in <50ms — the user knows the request was received
- The UI shows a loading spinner while waiting
- WebSocket delivers the result as soon as it's ready — no polling
- The backend uses a dedicated thread pool (`aiTaskExecutor`, core=2, max=5) so AI calls don't compete with HTTP request threads

### Q: How does idempotency work?

If the user clicks "Get AI Feedback" twice, or if the WebSocket delivers the result and the user refreshes the page, we don't want to call the AI API again. The service checks `aiFeedbackRepository.findBySubmissionId()` before calling the API. If feedback already exists, it re-sends the stored feedback via WebSocket.

### Q: Why is the AI service @ConditionalOnProperty?

```java
@Service
@ConditionalOnProperty(name = "coderank.ai.enabled", havingValue = "true")
public class AiFeedbackService { ... }
```

When `coderank.ai.enabled=false` (the default), this bean is not created at all. Benefits:
- No startup errors if the API key is missing
- The controller uses `@Autowired(required = false)` and returns `503 Service Unavailable` when the service is absent
- The rest of the application works perfectly without AI — it's a purely optional feature
- No accidental API charges in development/testing

### Q: How is the AI prompt constructed?

The prompt has two parts:

**System prompt (sets the AI's role):**
> "You are a competitive programming coach. Analyze the following code submission and provide constructive feedback. Focus on: code correctness, time/space complexity, code style, and potential improvements. Be concise and helpful."

**User prompt (submission context):**
```
## Code (Java)
```java
[user's source code]
```

## Verdict: WRONG_ANSWER
## Execution Time: 245ms
## Problem: Two Sum
[problem description]
## Test Cases: 3/5 passed
## Stderr:
[any stderr output]
```

This gives the AI full context: what the code does, what it was supposed to do, and how it performed. The AI can then provide targeted feedback like "your solution is O(n²), consider using a HashMap for O(n)."

### Q: Why is the AI provider configurable?

The service uses a standard OpenAI chat completions API contract (`POST /chat/completions`). Many providers implement this same API:

| Provider | Base URL |
|----------|----------|
| OpenAI | `https://api.openai.com/v1` (default) |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` |
| Ollama (local) | `http://localhost:11434/v1` |
| Azure OpenAI | `https://your-deployment.openai.azure.com/openai/deployments/your-model/` |
| LiteLLM | `http://localhost:4000/v1` |

By making `AI_BASE_URL`, `AI_MODEL`, and `AI_API_KEY` configurable via environment variables, users can swap providers without code changes. This avoids vendor lock-in and lets developers use free or local models during development.

---

## Database Design

### Q: Why PostgreSQL?

- Strong ACID compliance — submission data must not be lost or corrupted
- Native ENUM types — `verdict`, `submission_status`, `difficulty`, `test_case_visibility` are enforced at the database level, not just the application level
- JSONB support — problem `constraints` field stores a JSON array natively, with validation
- Flyway compatibility — well-supported migration target
- Docker-friendly — lightweight Alpine image available
- Single instance hosts both application data and Keycloak data (via separate databases)

### Q: Why Flyway for migrations?

Flyway provides versioned, repeatable schema migrations. Each migration is a numbered SQL file (`V1__init_schema.sql`, `V2__judge_and_ai.sql`) that runs once and only once:

- **Forward-only:** Migrations run in order and are never re-applied. If a migration is modified after being applied, Flyway detects the checksum mismatch and fails — preventing silent schema drift.
- **Team-friendly:** Migrations are committed to Git, so everyone runs the same schema changes in the same order.
- **Startup integration:** Runs automatically when the Spring Boot app starts — no manual SQL scripts.
- **Hibernate validate:** JPA is configured with `ddl-auto: validate`, meaning Hibernate checks that its entity model matches the schema but never alters tables itself. Schema changes only happen through Flyway.

### Q: Why Postgres-native ENUMs instead of VARCHAR?

```sql
CREATE TYPE verdict AS ENUM ('ACCEPTED', 'WRONG_ANSWER', ...);
```

Benefits over `VARCHAR`:
- **Type safety:** The database rejects invalid values. If application code tries to insert `"ACEPTED"` (typo), the INSERT fails instead of silently storing garbage.
- **Storage efficiency:** ENUMs are stored as integers internally (~4 bytes vs variable-length string)
- **Self-documenting:** `\dT+` in psql shows all valid values

Tradeoff: Adding new enum values requires a migration (`ALTER TYPE ... ADD VALUE`). But enum values in CodeRank (verdicts, statuses) are stable — they don't change often.

### Q: Why JSONB for problem constraints?

Problem constraints are a list of strings like `["2 <= nums.length <= 10^4", "Only one valid answer exists."]`. Options considered:

| Option | Why chosen/rejected |
|--------|-------------------|
| Separate `problem_constraints` table | Over-normalized for a simple string list. Adds a JOIN for every problem query. |
| TEXT column with delimiter | Fragile parsing, no validation |
| **JSONB column** | Native JSON validation, supports indexing, clean read/write from JPA |

JSONB validates that the content is valid JSON on insert. The JPA entity maps it as a `String` and the application parses/generates the JSON array.

### Q: Why does submissions.problem_id use ON DELETE SET NULL?

```sql
ALTER TABLE submissions ADD COLUMN problem_id UUID REFERENCES problems(id) ON DELETE SET NULL;
```

If a problem is deleted, existing submissions that reference it should not be deleted — they represent completed work with verdicts and execution history. Setting the foreign key to NULL preserves the submission data while breaking the link to the deleted problem.

Alternative `ON DELETE CASCADE` would delete all submissions when a problem is deleted — undesirable for data preservation.

### Q: How are test cases structured?

Each problem has multiple test cases in the `test_cases` table:

```
problem_id  |  input         |  expected_output  |  is_sample  |  order_index
uuid-1      |  "4\n2 7...\n9"|  "[0, 1]"        |  true       |  0
uuid-1      |  "3\n3 2 4\n6" |  "[1, 2]"        |  true       |  1
uuid-1      |  "2\n0 0\n0"   |  "[0, 1]"        |  false      |  2
```

- `is_sample`: Sample test cases are shown to the user in the problem description and always visible in results. Hidden test cases (`is_sample=false`) are only revealed based on the problem's visibility setting.
- `order_index`: Determines execution order. A unique constraint on `(problem_id, order_index)` prevents accidental duplicates.
- `input`: Raw text piped to stdin. Uses `\n` for newlines.
- `expected_output`: The exact string the program should print to stdout.

---

## Frontend Architecture

### Q: Why React + Vite + TypeScript?

| Choice | Reason |
|--------|--------|
| **React** | Component model is a natural fit for CodeRank's UI (editor panel, result panel, problem list). Large ecosystem. |
| **Vite** | Fast dev server with HMR. Build times under 3 seconds. No webpack configuration needed. |
| **TypeScript** | Catches bugs before runtime. Critical for the `SubmissionResponse`, `Problem`, and WebSocket message types — ensures frontend stays in sync with backend DTOs. |

### Q: Why Zustand for state management?

Zustand is minimal (~1KB) and provides global state without boilerplate. CodeRank uses three stores:

- **submissionStore** — tracks `currentSubmission` and `loading` state during code execution
- **authStore** — Keycloak authentication state (user, token, login/logout)
- **themeStore** — dark/light mode toggle (persisted)

Redux would be overkill for this scale. Zustand's `create()` + hooks pattern requires no providers, reducers, or action creators.

### Q: Why Monaco Editor?

Monaco is the editor engine behind VS Code. It provides:
- Java syntax highlighting out of the box
- Bracket matching and auto-indent
- Find/replace, multi-cursor editing
- Familiar keybindings (VS Code users feel at home)
- Theme support (matches dark/light mode toggle)

Alternatives like CodeMirror or Ace are lighter but lack the VS Code-like experience that makes CodeRank feel professional.

### Q: Why MUI (Material UI)?

MUI provides pre-built, accessible, themeable components. CodeRank uses:
- `Chip` — verdict badges (color-coded by verdict)
- `Paper` — result panels, problem cards
- `CircularProgress` — loading spinners during submission
- `Tabs` — switching between stdout/stderr/test cases in ResultPanel
- `Alert` — error messages
- `Button`, `TextField`, `Grid` — standard layout

Using a component library avoids building common UI patterns from scratch and ensures consistent styling.

### Q: How does the Vite proxy work?

The frontend runs on port 5173, the backend on port 8080. In production, a reverse proxy (nginx) would handle routing. In development, Vite's built-in proxy handles it:

```typescript
// vite.config.ts
proxy: {
  '/api': 'http://localhost:8080',    // REST API
  '/ws': {                             // WebSocket
    target: 'http://localhost:8080',
    ws: true,
  },
}
```

When the frontend makes a request to `/api/v1/problems`, Vite transparently forwards it to `http://localhost:8080/api/v1/problems`. This avoids CORS issues and makes the frontend code environment-agnostic (it just calls `/api/...`).

### Q: How does the frontend handle WebSocket messages from different sources?

The same WebSocket topic (`/topic/submissions/{id}`) carries two types of messages:

1. **Execution updates:** status transitions, verdict, stdout/stderr, test case results
2. **AI feedback:** generated code review

The frontend distinguishes them by checking the `type` field:

```typescript
client.subscribe(`/topic/submissions/${id}`, (message) => {
  const parsed = JSON.parse(message.body);
  if (parsed.type === 'AI_FEEDBACK' || parsed.type === 'AI_FEEDBACK_ERROR') {
    onAiFeedback?.(parsed);    // AI feedback handler
  } else {
    onUpdate(parsed);           // Execution update handler
  }
});
```

Execution updates don't have a `type` field, so any message without one is treated as a status update.

---

## WebSocket & Real-Time Updates

### Q: Why WebSocket instead of polling?

| Approach | Latency | Server load | Connection count |
|----------|---------|-------------|-----------------|
| Polling (every 1s) | Up to 1s | High (N requests/sec per client) | 1 per poll |
| Long polling | Near-instant | Medium | 1 persistent |
| **WebSocket** | Instant | Low (push-only) | 1 persistent |
| SSE (Server-Sent Events) | Instant | Low | 1 persistent |

WebSocket was chosen because:
- Submissions go through 3-4 state transitions in 2-10 seconds. Polling would either be too slow (miss transitions) or too frequent (waste bandwidth).
- STOMP protocol (over WebSocket) provides topic-based pub/sub natively, which maps perfectly to "subscribe to this submission's updates."
- Spring Boot has first-class STOMP support via `@MessageMapping` and `SimpMessagingTemplate`.

### Q: Why STOMP protocol?

STOMP (Simple Text Oriented Messaging Protocol) adds a thin pub/sub layer on top of raw WebSocket:

- **Topics:** Subscribe to `/topic/submissions/{id}` — the client only receives messages for its submission, not all submissions
- **Message routing:** The server publishes to a topic, and Spring routes it to all subscribed clients
- **Client libraries:** `@stomp/stompjs` is well-maintained and handles reconnection, heartbeats, and message framing

Without STOMP, we'd need to implement our own routing logic (parsing message types, managing client subscriptions).

### Q: How does the frontend handle WebSocket reconnection?

```typescript
const client = new Client({
  brokerURL: import.meta.env.VITE_WS_URL,
  reconnectDelay: 5000,  // retry every 5 seconds
});
```

If the WebSocket connection drops (backend restart, network issue), the STOMP client automatically reconnects every 5 seconds. On reconnection, it re-subscribes to the submission topic. If the submission completed while disconnected, the frontend falls back to polling (REST GET) to retrieve the final result.

---

## Authentication & Rate Limiting

### Q: Why Keycloak for authentication?

Keycloak is a full-featured identity provider that handles:
- User registration and login
- Password hashing and storage
- JWT token issuance and validation
- Session management and token refresh
- OAuth2/OIDC standards compliance

Using Keycloak means CodeRank never touches raw passwords. The backend acts as an OAuth2 Resource Server — it validates JWTs but doesn't issue them.

Alternatives like Firebase Auth or Auth0 are SaaS-dependent. Keycloak is self-hosted (runs in Docker alongside other services), open-source, and industry-standard.

### Q: Can I use CodeRank without Keycloak?

Yes. Keycloak is optional for basic usage:
- **Playground mode** works without authentication (guest submissions)
- **Judge mode** works without authentication
- **AI feedback** works without authentication
- **Submission history** requires authentication (needs a user identity)

If Keycloak is not configured, the backend logs a non-fatal warning about the missing realm. All "guest-allowed" endpoints work normally.

### Q: How does rate limiting work?

Rate limiting is applied only to `POST /api/v1/submissions` — the endpoint that triggers expensive Docker container creation.

The implementation uses **Bucket4j** (token-bucket algorithm):
- Each user/IP gets a bucket with N tokens (requests per minute)
- Each submission consumes 1 token
- Tokens refill at a fixed rate
- When the bucket is empty, requests get `429 Too Many Requests`

| User type | Limit | Bucket key |
|-----------|-------|-----------|
| Guest | 5 req/min | IP address |
| Authenticated | 20 req/min | JWT principal name |

Rate limits are configurable in `application.yml` and backed by Redis for distributed counting (works across multiple API instances).

---

## Extension System (SPI)

### Q: What is the Extension System?

CodeRank includes a Service Provider Interface (SPI) that lets you hook into the submission lifecycle:

```java
public interface SubmissionEventListener {
    default void onSubmissionReceived(Submission submission) {}
    default void onSubmissionComplete(Submission submission, ExecutionResult result) {}
    default void onSubmissionFailed(Submission submission, String error) {}
}
```

Any Spring `@Component` implementing this interface is auto-discovered and invoked by `SubmissionEventPublisher` at the appropriate lifecycle points.

### Q: When are the lifecycle events fired?

```
Submission received (dequeued from RabbitMQ)
    → onSubmissionReceived()
        ↓
Execution pipeline (compile → run → judge)
        ↓
Success:
    → onSubmissionComplete(submission, result)
Failure:
    → onSubmissionFailed(submission, errorMessage)
```

### Q: What can I build with the SPI?

Examples:
- **Logging plugin:** Log submission metadata to an external analytics system
- **Webhook plugin:** POST results to Slack/Discord when a submission completes
- **Plagiarism checker:** Compare source code against previous submissions
- **Leaderboard updater:** Update ranking scores on accepted submissions
- **Notification plugin:** Email users when their long-running submission completes

All methods are `default` (no-op), so you only override the events you care about.

---

## Miscellaneous

### Q: Why is the project structured as two separate directories?

```
coderank/
├── coderank-api/    # Spring Boot backend (Maven)
├── coderank-ui/     # React frontend (npm)
```

Separate directories with separate build tools because:
- Backend and frontend have different build lifecycles (Maven vs npm)
- Different deployment targets (JAR vs static files)
- Different CI/CD pipelines
- Teams can work on frontend and backend independently
- No monorepo tooling overhead (nx, turborepo) needed at this scale

### Q: Why Java 21?

- **Long-term support (LTS)** — production-stable, supported until 2031
- **Records** — used extensively for DTOs (`SubmissionResponse`, `JudgeResult`, `ContainerExecution`). Eliminates boilerplate.
- **Pattern matching for switch** — used in `filterTestCaseResults()` for clean visibility filtering
- **Virtual threads** (available but not yet used) — future optimization for worker concurrency
- **Same JDK in sandbox** — the sandbox runs JDK 21, so users write Java 21 code that the backend also understands

### Q: Why Spring Boot 3.3?

- Native support for Java 21 features
- Built-in OAuth2 Resource Server (JWT validation)
- Built-in WebSocket/STOMP support
- Spring Data JPA for database access
- RabbitMQ integration via `spring-boot-starter-amqp`
- @Async + ThreadPoolTaskExecutor for non-blocking AI calls
- @ConditionalOnProperty for optional feature loading
- Mature ecosystem with extensive documentation

### Q: What happens if Docker is not running?

The backend starts successfully but submissions fail with:
```
com.github.dockerjava.api.exception.DockerException: Cannot connect to the Docker daemon
```

The health endpoint (`/api/v1/health`) still returns `200 OK` because it checks API liveness, not Docker connectivity. The failure only surfaces when a submission is dequeued and the worker tries to create a container.

### Q: How are problems seeded into the database?

The Flyway V2 migration (`V2__judge_and_ai.sql`) includes INSERT statements that seed 4 starter problems:

1. **Two Sum** (Easy) — 2 sample + 3 hidden test cases
2. **FizzBuzz** (Easy) — 2 sample + 3 hidden test cases
3. **Palindrome Check** (Medium) — 2 sample + 4 hidden test cases
4. **Reverse String** (Easy) — 2 sample + 3 hidden test cases

Each problem includes starter code (a `Main.java` template with the main method and I/O boilerplate pre-written) so users only need to implement the core algorithm.

New problems can be added via a new Flyway migration (e.g., `V3__add_more_problems.sql`) or a future admin API.

### Q: What is the `wsChannel` field in the submission response?

The `wsChannel` field is a convenience string like `/topic/submissions/a1b2c3d4-...`. It tells the client exactly which STOMP topic to subscribe to for real-time updates on this submission. This avoids the client having to construct the topic path manually.

### Q: How does the frontend handle state when navigating between problems?

When the user navigates from one problem to another (e.g., Two Sum → FizzBuzz), the `ProblemView` component:

1. Resets the submission store (clears previous verdict, output, test results)
2. Clears AI feedback state
3. Unsubscribes from the previous submission's WebSocket topic
4. Fetches the new problem from the API
5. Resets the editor to the new problem's starter code

This prevents stale results from one problem bleeding into another problem's view.
