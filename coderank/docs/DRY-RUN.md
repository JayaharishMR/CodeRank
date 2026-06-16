# CodeRank Dry Run: Full Request Lifecycle

This document traces a complete code submission through every layer of the CodeRank system, from the initial HTTP request to the final WebSocket push. It uses concrete values throughout so you can follow exactly what happens at each stage.

---

## 1. Scenario Setup

A guest user (no account, no JWT token) submits a Hello World Java program from their terminal:

```bash
curl -X POST http://localhost:8080/api/v1/submissions \
  -H "Content-Type: application/json" \
  -d '{"language":"JAVA","sourceCode":"public class Main { public static void main(String[] args) { System.out.println(\"Hello CodeRank!\"); } }"}'
```

The server is running on port 8080 with PostgreSQL on 5432, RabbitMQ on 5673, and Docker available on the host.

---

## 2. Step-by-Step Trace

### Step 1: HTTP Request Arrives

**Where:** Embedded Tomcat (port 8080) -> Spring Security filter chain

The raw HTTP request:

```
POST /api/v1/submissions HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{"language":"JAVA","sourceCode":"public class Main { public static void main(String[] args) { System.out.println(\"Hello CodeRank!\"); } }"}
```

Spring Security's `SecurityFilterChain` (configured in `SecurityConfig.securityFilterChain()`) evaluates the request against its authorization rules:

```java
.requestMatchers(HttpMethod.POST, "/api/v1/submissions").permitAll()
```

The request is `POST /api/v1/submissions`, which matches this rule. `permitAll()` means no JWT is required. The request proceeds through the filter chain without authentication.

Since there is no `Authorization` header, Spring Security's OAuth2 resource server filter does not attempt JWT validation. The `SecurityContext` will contain an anonymous authentication token. When the controller later injects `@AuthenticationPrincipal Jwt jwt`, that parameter will be **null**.

**Control passes to:** `RateLimitFilter` (next filter in the chain)

---

### Step 2: Rate Limiting

**Where:** `RateLimitFilter.doFilterInternal()`
**File:** `com.coderank.config.RateLimitFilter`

The filter checks whether the request should be rate-limited:

```java
if (!request.getRequestURI().startsWith("/api/v1/submissions") || !"POST".equals(request.getMethod())) {
    filterChain.doFilter(request, response);  // skip rate limiting
    return;
}
```

Our request is `POST /api/v1/submissions` -- both conditions match, so rate limiting applies.

**Resolve the bucket key:**

```java
private String resolveKey(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    // auth is present but principal is "anonymousUser" -- treated as guest
    if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
        return "user:" + auth.getName();
    }
    return "guest:" + request.getRemoteAddr();
}
```

No real authentication exists for this request. The principal is `"anonymousUser"`, so the method falls through to the guest branch.

**Result:** `key = "guest:127.0.0.1"`

**Create or retrieve the bucket:**

```java
Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket(isAuthenticated()));
```

This is the first request from this IP. `isAuthenticated()` returns `false`. A new bucket is created with the guest rate limit from `RateLimitConfig`:

```java
// From application.yml: coderank.rate-limit.guest-requests-per-minute: 5
int rpm = 5;
Bucket.builder()
    .addLimit(Bandwidth.builder()
        .capacity(5)
        .refillGreedy(5, Duration.ofMinutes(1))
        .build())
    .build();
```

The bucket starts with **5 tokens**. Greedy refill means all 5 tokens are restored at once every 60 seconds.

**Consume a token:**

```java
if (bucket.tryConsume(1)) {         // 5 -> 4 tokens remaining
    filterChain.doFilter(request, response);  // pass through
}
```

Token consumed successfully (4 remaining). Request proceeds.

#### What happens on the 6th request within the same minute?

The bucket has 0 tokens remaining. `tryConsume(1)` returns `false`:

```java
} else {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());  // 429
    response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
}
```

The client receives:

```
HTTP/1.1 429 Too Many Requests

{"error":"Rate limit exceeded"}
```

The request never reaches the controller. The client must wait until the bucket refills (up to 60 seconds).

**Control passes to:** Spring's `DispatcherServlet` -> `SubmissionController`

---

### Step 3: Request Validation

**Where:** Spring MVC deserialization and Bean Validation
**File:** `com.coderank.dto.SubmissionRequest`

Spring's `@RequestBody` processing deserializes the JSON into a `SubmissionRequest` record:

```java
public record SubmissionRequest(
    @NotNull Language language,          // "JAVA" -> Language.JAVA
    @NotBlank @Size(max = 51200) String sourceCode,  // the source string
    String stdin                         // absent in JSON -> null
) {}
```

After deserialization, the `@Valid` annotation on the controller parameter triggers Bean Validation:

| Field        | Value                                      | Constraint          | Result |
|--------------|--------------------------------------------|---------------------|--------|
| `language`   | `Language.JAVA`                            | `@NotNull`          | PASS   |
| `sourceCode` | `"public class Main { public static..."` (88 chars) | `@NotBlank`, `@Size(max=51200)` | PASS |
| `stdin`      | `null`                                     | (no constraints)    | PASS   |

All validations pass. The deserialized object:

```
SubmissionRequest[language=JAVA, sourceCode="public class Main { public static void main(String[] args) { System.out.println(\"Hello CodeRank!\"); } }", stdin=null]
```

#### What happens with empty sourceCode?

If the client sends `{"language":"JAVA","sourceCode":""}`, the `@NotBlank` constraint fails. Spring throws `MethodArgumentNotValidException`, which is caught by `GlobalExceptionHandler`:

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
    return ResponseEntity.badRequest().body(Map.of("error", message));
}
```

The client receives:

```
HTTP/1.1 400 Bad Request

{"error":"sourceCode: must not be blank"}
```

**Control passes to:** `SubmissionController.submit()`

---

### Step 4: Controller Handling

**Where:** `SubmissionController.submit()`
**File:** `com.coderank.controller.SubmissionController`

```java
@PostMapping
@ResponseStatus(HttpStatus.ACCEPTED)
public SubmissionResponse submit(
        @Valid @RequestBody SubmissionRequest request,
        @AuthenticationPrincipal Jwt jwt) {
    String userId = (jwt != null) ? jwt.getSubject() : null;
    Submission submission = submissionService.submit(request, null);
    return toResponse(submission);
}
```

- `jwt` is **null** (guest user, no Authorization header)
- `userId` is set to **null**
- Calls `submissionService.submit(request, null)`

**Control passes to:** `SubmissionService.submit()`

---

### Step 5: Service -- Save and Enqueue

**Where:** `SubmissionService.submit()`
**File:** `com.coderank.service.SubmissionService`

```java
public Submission submit(SubmissionRequest request, String keycloakUserId) {
    Submission submission = new Submission();
    submission.setLanguage(request.language());        // JAVA
    submission.setSourceCode(request.sourceCode());    // "public class Main..."
    submission.setStdin(request.stdin());              // null
    submission.setStatus(SubmissionStatus.QUEUED);     // QUEUED

    if (keycloakUserId != null) {
        // keycloakUserId is null for guests -- skip
    }

    submission = submissionRepository.save(submission);

    rabbitTemplate.convertAndSend("coderank.submissions", submission.getId().toString());
    return submission;
}
```

**After `new Submission()` and setters, before save:**

| Field             | Value                                                   |
|-------------------|---------------------------------------------------------|
| `id`              | `null` (JPA will generate)                              |
| `userId`          | `null`                                                  |
| `language`        | `JAVA`                                                  |
| `sourceCode`      | `public class Main { public static void main(String[]...` |
| `stdin`           | `null`                                                  |
| `status`          | `QUEUED`                                                |
| `verdict`         | `PENDING` (entity default)                              |
| `stdout`          | `null`                                                  |
| `stderr`          | `null`                                                  |
| `executionTimeMs` | `null`                                                  |
| `memoryUsedKb`    | `null`                                                  |
| `errorMessage`    | `null`                                                  |
| `createdAt`       | `null` (set by `@PrePersist`)                           |
| `updatedAt`       | `null` (set by `@PrePersist`)                           |

**5a. Save to PostgreSQL:**

`submissionRepository.save(submission)` triggers Hibernate. The `@PrePersist` callback fires:

```java
@PrePersist
protected void onCreate() {
    Instant now = Instant.now();  // 2026-05-24T10:12:45.231Z
    this.createdAt = now;
    this.updatedAt = now;
}
```

Hibernate generates and executes this SQL:

```sql
INSERT INTO submissions (
    id, user_id, language, source_code, stdin,
    status, verdict, stdout, stderr,
    execution_time_ms, memory_used_kb, error_message,
    created_at, updated_at
) VALUES (
    'a1b2c3d4-e5f6-7890-abcd-ef1234567890',    -- UUID generated by @GeneratedValue(strategy = UUID)
    NULL,                                         -- guest user
    'JAVA',                                       -- EnumType.STRING
    'public class Main { public static void main(String[] args) { System.out.println("Hello CodeRank!"); } }',
    NULL,                                         -- no stdin
    'QUEUED',                                     -- initial status
    'PENDING',                                    -- initial verdict
    NULL, NULL, NULL, NULL, NULL,                 -- all output fields null
    '2026-05-24T10:12:45.231Z',                  -- createdAt
    '2026-05-24T10:12:45.231Z'                   -- updatedAt
);
```

The submission now exists in the database with `id = a1b2c3d4-e5f6-7890-abcd-ef1234567890`.

**5b. Publish to RabbitMQ:**

```java
rabbitTemplate.convertAndSend("coderank.submissions", submission.getId().toString());
```

This publishes the string `"a1b2c3d4-e5f6-7890-abcd-ef1234567890"` to the durable queue `coderank.submissions`. Only the UUID is sent -- the worker will fetch the full entity from the database. This keeps the message payload small and avoids serialization coupling.

The message is now sitting in the RabbitMQ queue, waiting for a consumer.

**Control returns to:** `SubmissionController.submit()` with the saved `Submission` entity.

---

### Step 6: HTTP Response

**Where:** `SubmissionController.toResponse()`
**File:** `com.coderank.controller.SubmissionController`

The controller maps the entity to a response DTO:

```java
private SubmissionResponse toResponse(Submission s) {
    return new SubmissionResponse(
        s.getId(),              // a1b2c3d4-e5f6-7890-abcd-ef1234567890
        s.getStatus(),          // QUEUED
        s.getVerdict(),         // PENDING
        s.getStdout(),          // null
        s.getStderr(),          // null
        s.getExecutionTimeMs(), // null
        s.getMemoryUsedKb(),    // null
        s.getErrorMessage(),    // null
        "/topic/submissions/" + s.getId(),  // WebSocket channel
        s.getCreatedAt()        // 2026-05-24T10:12:45.231Z
    );
}
```

`@ResponseStatus(HttpStatus.ACCEPTED)` makes the response HTTP 202. The client receives:

```
HTTP/1.1 202 Accepted
Content-Type: application/json

{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "QUEUED",
  "verdict": "PENDING",
  "stdout": null,
  "stderr": null,
  "executionTimeMs": null,
  "memoryUsedKb": null,
  "errorMessage": null,
  "wsChannel": "/topic/submissions/a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "createdAt": "2026-05-24T10:12:45.231Z"
}
```

The client now has two important pieces of information:
1. **`id`** -- to poll or reference the submission later
2. **`wsChannel`** -- the STOMP topic to subscribe to for real-time execution updates

The HTTP request-response cycle is now complete. Everything from here on happens asynchronously.

---

### Step 7: WebSocket Connection (Parallel)

**Where:** `WebSocketConfig` (server-side), client-side JavaScript
**File:** `com.coderank.config.WebSocketConfig`

The client opens a WebSocket connection using SockJS for fallback support:

```javascript
const socket = new SockJS('http://localhost:8080/ws/execution');
const stompClient = Stomp.over(socket);

stompClient.connect({}, function(frame) {
    stompClient.subscribe(
        '/topic/submissions/a1b2c3d4-e5f6-7890-abcd-ef1234567890',
        function(message) {
            const update = JSON.parse(message.body);
            console.log('Status:', update.status, 'Verdict:', update.verdict);
        }
    );
});
```

On the server side, the WebSocket endpoint is registered at `/ws/execution` with SockJS enabled:

```java
registry.addEndpoint("/ws/execution")
    .setAllowedOrigins("*")
    .withSockJS();
```

The `/ws/**` path is `permitAll()` in `SecurityConfig`, so no JWT is needed for the WebSocket handshake.

The message broker is configured with:
- `/topic` prefix for server-to-client broadcasts
- `/app` prefix for client-to-server messages (not used in this flow)

The client is now subscribed to `/topic/submissions/a1b2c3d4-e5f6-7890-abcd-ef1234567890` and will receive every status update pushed to that topic.

---

### Step 8: Worker Picks Up Job

**Where:** `ExecutionWorker.processSubmission()`
**File:** `com.coderank.worker.ExecutionWorker`

The `@RabbitListener` annotation on `processSubmission` makes this method a consumer of the `coderank.submissions` queue. When the message arrives:

```java
@RabbitListener(queues = RabbitMQConfig.SUBMISSION_QUEUE)  // "coderank.submissions"
public void processSubmission(String submissionIdStr) {
    UUID submissionId = UUID.fromString(submissionIdStr);
    // submissionId = a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

**8a. Fetch from database:**

```java
Submission submission = submissionRepository.findById(submissionId).orElse(null);
```

Hibernate executes:

```sql
SELECT * FROM submissions WHERE id = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';
```

Returns the full `Submission` entity with `status=QUEUED`, `verdict=PENDING`.

**8b. Status transition: QUEUED -> COMPILING**

```java
updateStatus(submission, SubmissionStatus.COMPILING);
```

Inside `updateStatus`:

```java
private void updateStatus(Submission submission, SubmissionStatus status) {
    submission.setStatus(status);          // status = COMPILING
    submissionRepository.save(submission); // UPDATE submissions SET status='COMPILING', updated_at=... WHERE id=...
    sendNotification(submission);          // push to WebSocket
}
```

WebSocket notification sent to `/topic/submissions/a1b2c3d4-e5f6-7890-abcd-ef1234567890`:

```json
{
  "submissionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPILING",
  "verdict": "PENDING",
  "stdout": null,
  "stderr": null,
  "executionTimeMs": null,
  "memoryUsedKb": null,
  "errorMessage": null,
  "timestamp": "2026-05-24T10:12:46.102Z"
}
```

**8c. Status transition: COMPILING -> RUNNING**

```java
updateStatus(submission, SubmissionStatus.RUNNING);
```

Same pattern. WebSocket notification:

```json
{
  "submissionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "RUNNING",
  "verdict": "PENDING",
  "stdout": null,
  "stderr": null,
  "executionTimeMs": null,
  "memoryUsedKb": null,
  "errorMessage": null,
  "timestamp": "2026-05-24T10:12:46.118Z"
}
```

**8d. Execute in sandbox:**

```java
ExecutionResult result = sandboxManager.execute(submission);
```

**Control passes to:** `DockerSandboxManager.execute()`

---

### Step 9: Docker Sandbox Execution

**Where:** `DockerSandboxManager.execute()`
**File:** `com.coderank.worker.DockerSandboxManager`

This is the most complex step. The method creates an isolated Docker container, runs the user's code inside it, collects output, and cleans up. Here is the full trace.

#### 9a. Temp Directory Creation

```java
Path tempDir = Files.createTempDirectory("coderank-");
// tempDir = /tmp/coderank-8472619305213/

Path sourceFile = tempDir.resolve("Main.java");
Files.writeString(sourceFile, submission.getSourceCode());
// Writes: /tmp/coderank-8472619305213/Main.java
// Content: public class Main { public static void main(String[] args) { System.out.println("Hello CodeRank!"); } }
```

Since `submission.getStdin()` is `null`, no `stdin.txt` is created.

**Set permissions** so the container's non-root user can read the files (rootless Docker UID remapping):

```java
Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwxr-xr-x"));    // 755
Files.setPosixFilePermissions(sourceFile, PosixFilePermissions.fromString("rw-r--r--"));  // 644
```

**Host filesystem at this point:**

```
/tmp/coderank-8472619305213/
  Main.java   (644)  -- "public class Main { public static void main..."
```

#### 9b. Container Creation

```java
HostConfig hostConfig = HostConfig.newHostConfig()
    .withMemory(256L * 1024 * 1024)            // 268435456 bytes = 256 MB
    .withCpuCount(1L)                           // 1 CPU core
    .withPidsLimit(50L)                         // max 50 processes (fork bomb protection)
    .withNetworkMode("none")                    // NO network access
    .withReadonlyRootfs(false)                  // container needs writable /tmp for javac output
    .withCapDrop(Capability.ALL)                // drop ALL Linux capabilities
    .withSecurityOpts(List.of("no-new-privileges"))  // block setuid/setgid
    .withBinds(new Bind(
        "/tmp/coderank-8472619305213",          // host path
        new Volume("/workspace")                // container path
    ));

CreateContainerResponse container = dockerClient.createContainerCmd("coderank-sandbox-java")
    .withHostConfig(hostConfig)
    .exec();

containerId = container.getId();
// containerId = "f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3"
```

**Security constraints summary:**

| Constraint         | Value                | Purpose                                      |
|--------------------|----------------------|----------------------------------------------|
| Memory limit       | 256 MB               | Prevent OOM-killing the host                 |
| CPU count          | 1                    | Limit CPU consumption                        |
| PID limit          | 50                   | Prevent fork bombs                           |
| Network mode       | `none`               | No internet, no exfiltration, no lateral move|
| Capabilities       | ALL dropped          | No mount, chown, or any privileged syscall   |
| Security opts      | `no-new-privileges`  | Block setuid/setgid escalation               |
| Bind mount         | host dir -> /workspace| Source code available inside container       |

#### 9c. Container Starts and Entrypoint Executes

```java
dockerClient.startContainerCmd(containerId).exec();
```

Inside the container, `entrypoint.sh` runs. Here is what happens step by step:

**Step 1 of entrypoint: Create output directory**

```bash
mkdir -p /tmp/out
```

Creates `/tmp/out` inside the container for compiled `.class` files.

**Step 2 of entrypoint: Compile**

```bash
javac -d /tmp/out /workspace/*.java 2>/tmp/compile_error.txt
COMPILE_EXIT=$?
```

This compiles `/workspace/Main.java`. The Java compiler:
- Reads `Main.java` from `/workspace` (bind-mounted from host)
- Produces `Main.class` in `/tmp/out/`
- `COMPILE_EXIT = 0` (compilation succeeds)

Since `COMPILE_EXIT` is 0, the error branch is skipped:

```bash
if [ $COMPILE_EXIT -ne 0 ]; then
    echo "COMPILE_ERROR"       # NOT reached
    cat /tmp/compile_error.txt
    exit 1
fi
```

**Step 3 of entrypoint: Detect main class**

```bash
MAIN_CLASS=$(grep -rl 'public static void main' /workspace/*.java | head -1 | xargs basename | sed 's/.java//')
```

Breakdown:
1. `grep -rl 'public static void main' /workspace/*.java` -> `/workspace/Main.java`
2. `head -1` -> `/workspace/Main.java`
3. `xargs basename` -> `Main.java`
4. `sed 's/.java//'` -> `Main`

**Result:** `MAIN_CLASS = "Main"`

The variable is not empty, so the "no main method" error branch is skipped.

**Step 4 of entrypoint: Run**

```bash
if [ -f /workspace/stdin.txt ]; then
    java -cp /tmp/out "$MAIN_CLASS" < /workspace/stdin.txt
else
    java -cp /tmp/out "$MAIN_CLASS"    # <-- this branch
fi
```

No `stdin.txt` exists (guest did not provide stdin), so the program runs without input:

```bash
java -cp /tmp/out Main
```

The JVM executes `Main.main()`, which calls `System.out.println("Hello CodeRank!")`.

**Container stdout:** `Hello CodeRank!\n`
**Container stderr:** (empty)
**Container exit code:** `0`

#### 9d. Wait and Collect Output

```java
int exitCode = dockerClient.waitContainerCmd(containerId)
    .exec(new WaitContainerResultCallback())
    .awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);  // timeout = 10 seconds
// exitCode = 0
```

The container finished well within the 10-second timeout.

**Collect logs:**

```java
String stdout = collectLogs(containerId, true);   // "Hello CodeRank!\n"
String stderr = collectLogs(containerId, false);   // null (empty -> null)
```

The `collectLogs` method uses a `ResultCallback.Adapter<Frame>` with a `CountDownLatch` to collect container output. The latch has a 5-second safety timeout to prevent hanging if the Docker daemon stalls.

**Truncation check:**

```java
if (stdout != null && stdout.length() > maxStdoutBytes) {  // 17 < 65536
    stdout = stdout.substring(0, maxStdoutBytes) + "\n[output truncated]";
}
// No truncation needed
```

**Elapsed time:**

```java
long elapsed = System.currentTimeMillis() - startTime;  // ~823ms
```

#### 9e. Verdict Determination

```java
if (exitCode == 0) {
    return new ExecutionResult(Verdict.ACCEPTED, stdout, stderr, elapsed, null, null);
}
```

Exit code is 0, so the verdict is `ACCEPTED`.

**Returned ExecutionResult:**

```
ExecutionResult[
  verdict       = ACCEPTED,
  stdout        = "Hello CodeRank!\n",
  stderr        = null,
  executionTimeMs = 823,
  memoryUsedKb  = null,
  errorMessage  = null
]
```

#### 9f. Cleanup (in `finally` block)

Regardless of success or failure, the `finally` block always runs:

```java
// Remove the Docker container
dockerClient.removeContainerCmd("f4a3b2c1d0e9...").withForce(true).exec();

// Delete the temp directory and all files inside it
Files.walk(tempDir)                          // /tmp/coderank-8472619305213/
    .sorted(Comparator.reverseOrder())       // files before directories
    .forEach(p -> Files.delete(p));
```

After cleanup:
- Container `f4a3b2c1...` is removed from Docker
- `/tmp/coderank-8472619305213/` and its contents are deleted

**Control returns to:** `ExecutionWorker.processSubmission()`

---

### Step 10: Result Processing

**Where:** `ExecutionWorker.processSubmission()` (continued)
**File:** `com.coderank.worker.ExecutionWorker`

Back in the worker, after `sandboxManager.execute()` returns:

```java
ExecutionResult result = sandboxManager.execute(submission);
// result = ExecutionResult[ACCEPTED, "Hello CodeRank!\n", null, 823, null, null]

submission.setStatus(SubmissionStatus.COMPLETED);
submission.setVerdict(result.verdict());          // ACCEPTED
submission.setStdout(result.stdout());            // "Hello CodeRank!\n"
submission.setStderr(result.stderr());            // null
submission.setExecutionTimeMs(result.executionTimeMs());  // 823
submission.setMemoryUsedKb(result.memoryUsedKb());        // null
submission.setErrorMessage(result.errorMessage());        // null
```

**Guest submission check:**

```java
if (submission.getUserId() != null) {
    submissionRepository.save(submission);
}
```

`userId` is **null** (guest user). The submission is NOT saved back to the database. Guest submissions are fire-and-forget: the result is delivered over WebSocket but not persisted. This prevents the database from filling up with anonymous runs.

Note: The initial QUEUED row still exists in the database (from Step 5a), with intermediate COMPILING/RUNNING status updates saved during Step 8. But the final COMPLETED result is not persisted for guests.

**Send final WebSocket notification:**

```java
sendNotification(submission);
```

**Control passes to:** `WebSocketNotificationService`

---

### Step 11: WebSocket Push

**Where:** `WebSocketNotificationService.sendStatusUpdate()` and `ExecutionWorker.sendNotification()`
**File:** `com.coderank.service.WebSocketNotificationService`

The worker constructs the final `ExecutionStatusMessage`:

```java
new ExecutionStatusMessage(
    "a1b2c3d4-e5f6-7890-abcd-ef1234567890",  // submissionId
    SubmissionStatus.COMPLETED,                // status
    Verdict.ACCEPTED,                          // verdict
    "Hello CodeRank!\n",                       // stdout
    null,                                       // stderr
    823L,                                       // executionTimeMs
    null,                                       // memoryUsedKb
    null,                                       // errorMessage
    Instant.now()                               // 2026-05-24T10:12:48.053Z
)
```

The notification service publishes it:

```java
messagingTemplate.convertAndSend(
    "/topic/submissions/a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    message
);
```

The client's WebSocket subscription receives the STOMP message. The JSON payload:

```json
{
  "submissionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPLETED",
  "verdict": "ACCEPTED",
  "stdout": "Hello CodeRank!\n",
  "stderr": null,
  "executionTimeMs": 823,
  "memoryUsedKb": null,
  "errorMessage": null,
  "timestamp": "2026-05-24T10:12:48.053Z"
}
```

The full lifecycle is complete. The client received three WebSocket messages in total:
1. `status: COMPILING` (Step 8b)
2. `status: RUNNING` (Step 8c)
3. `status: COMPLETED, verdict: ACCEPTED, stdout: "Hello CodeRank!\n"` (Step 11)

---

## 3. Alternative Scenarios

### A. Compilation Error

**Example submission:** Missing semicolon

```json
{"language":"JAVA","sourceCode":"public class Main { public static void main(String[] args) { System.out.println(\"Hello\") } }"}
```

Steps 1-8 are identical. The flow diverges inside the Docker container at Step 9c.

**In entrypoint.sh:**

```bash
javac -d /tmp/out /workspace/*.java 2>/tmp/compile_error.txt
COMPILE_EXIT=$?
# COMPILE_EXIT = 1 (compilation failed)

if [ $COMPILE_EXIT -ne 0 ]; then
    echo "COMPILE_ERROR"
    cat /tmp/compile_error.txt
    exit 1
fi
```

**Container stdout:**
```
COMPILE_ERROR
/workspace/Main.java:1: error: ';' expected
public class Main { public static void main(String[] args) { System.out.println("Hello") } }
                                                                                         ^
1 error
```

**Container exit code:** `1`

**Back in DockerSandboxManager (Step 9e):**

```java
} else if (stdout != null && stdout.trim().startsWith("COMPILE_ERROR")) {
    String compileError = stdout.trim().replaceFirst("COMPILE_ERROR\n?", "");
    return new ExecutionResult(Verdict.COMPILATION_ERROR, null, compileError, elapsed, null, null);
}
```

The `COMPILE_ERROR` sentinel prefix is stripped, and the compiler diagnostics go into `stderr` of the result.

**ExecutionResult:**
```
ExecutionResult[COMPILATION_ERROR, null, "/workspace/Main.java:1: error: ';' expected...", 412, null, null]
```

**Final WebSocket message:**

```json
{
  "submissionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPLETED",
  "verdict": "COMPILATION_ERROR",
  "stdout": null,
  "stderr": "/workspace/Main.java:1: error: ';' expected\npublic class Main { ... }\n                                                                                         ^\n1 error",
  "executionTimeMs": 412,
  "memoryUsedKb": null,
  "errorMessage": null,
  "timestamp": "2026-05-24T10:12:47.643Z"
}
```

---

### B. Runtime Error

**Example submission:** NullPointerException

```json
{"language":"JAVA","sourceCode":"public class Main { public static void main(String[] args) { String s = null; System.out.println(s.length()); } }"}
```

Compilation succeeds (the code is syntactically valid). `entrypoint.sh` reaches Step 3 and runs `java -cp /tmp/out Main`.

The JVM throws `NullPointerException`. The process exits with a non-zero code (typically 1).

**Back in DockerSandboxManager (Step 9e):**

```java
// exitCode = 1, stdout does NOT start with "COMPILE_ERROR"
} else {
    return new ExecutionResult(Verdict.RUNTIME_ERROR, stdout, stderr, elapsed, null,
            "Process exited with code " + exitCode);
}
```

**ExecutionResult:**
```
ExecutionResult[RUNTIME_ERROR, null, "Exception in thread \"main\" java.lang.NullPointerException...", 534, null, "Process exited with code 1"]
```

---

### C. Time Limit Exceeded

**Example submission:** Infinite loop

```json
{"language":"JAVA","sourceCode":"public class Main { public static void main(String[] args) { while(true) {} } }"}
```

Compilation succeeds. The program enters an infinite loop. In `DockerSandboxManager`:

```java
int exitCode = dockerClient.waitContainerCmd(containerId)
    .exec(new WaitContainerResultCallback())
    .awaitStatusCode(10, TimeUnit.SECONDS);  // blocks for 10 seconds, then throws
```

After 10 seconds, `awaitStatusCode` throws an exception. The catch block runs:

```java
} catch (Exception e) {
    try { dockerClient.killContainerCmd(containerId).exec(); } catch (Exception ignored) {}
    long elapsed = System.currentTimeMillis() - startTime;
    return new ExecutionResult(Verdict.TIME_LIMIT_EXCEEDED, null, null, elapsed, null,
            "Execution timed out after 10 seconds");
}
```

The container is force-killed immediately to free resources. The `finally` block then removes the dead container and deletes the temp directory.

**ExecutionResult:**
```
ExecutionResult[TIME_LIMIT_EXCEEDED, null, null, 10023, null, "Execution timed out after 10 seconds"]
```

**Final WebSocket message:**

```json
{
  "submissionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPLETED",
  "verdict": "TIME_LIMIT_EXCEEDED",
  "stdout": null,
  "stderr": null,
  "executionTimeMs": 10023,
  "memoryUsedKb": null,
  "errorMessage": "Execution timed out after 10 seconds",
  "timestamp": "2026-05-24T10:12:56.254Z"
}
```

---

### D. Rate Limit Exceeded

The guest rate limit is **5 requests per minute** (from `application.yml`).

Requests 1-5 consume tokens from the bucket: 5 -> 4 -> 3 -> 2 -> 1 -> 0.

On the 6th request (within the same minute), `RateLimitFilter.doFilterInternal()` runs:

```java
if (bucket.tryConsume(1)) {   // 0 tokens -> returns false
    // NOT reached
} else {
    response.setStatus(429);
    response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
}
```

The request is rejected before it ever reaches `SubmissionController`. No submission is created, no database write occurs, no RabbitMQ message is published.

```
HTTP/1.1 429 Too Many Requests

{"error":"Rate limit exceeded"}
```

The bucket uses greedy refill: all 5 tokens are restored at once after 60 seconds.

---

### E. Authenticated User

The client includes a valid JWT from Keycloak:

```bash
curl -X POST http://localhost:8080/api/v1/submissions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -d '{"language":"JAVA","sourceCode":"public class Main { public static void main(String[] args) { System.out.println(\"Hello CodeRank!\"); } }"}'
```

**Differences from guest flow:**

1. **Step 1 (Security):** Spring's OAuth2 resource server filter validates the JWT against the Keycloak issuer URI (`http://localhost:9090/realms/coderank`). Signature, expiration, and audience are checked. The `SecurityContext` is populated with a real authentication.

2. **Step 2 (Rate Limiting):** `resolveKey()` finds a real authenticated principal:
   ```
   key = "user:b7e9f1a2-3c4d-5e6f-7a8b-9c0d1e2f3a4b"   (Keycloak subject ID)
   ```
   The bucket uses the higher authenticated limit: **20 requests per minute**.

3. **Step 4 (Controller):** `@AuthenticationPrincipal Jwt jwt` is NOT null:
   ```java
   String userId = jwt.getSubject();
   // userId = "b7e9f1a2-3c4d-5e6f-7a8b-9c0d1e2f3a4b"
   ```

4. **Step 5 (Service):** `keycloakUserId` is not null, but the TODO block has not been wired up yet:
   ```java
   if (keycloakUserId != null) {
       // TODO: Look up or create the internal User entity from the Keycloak subject ID.
       //       Until this is wired up, even authenticated users behave like guests.
   }
   ```
   Currently, `userId` on the Submission entity remains `null` even for authenticated users.

5. **Step 10 (Result Processing):** Once the TODO is implemented, `submission.getUserId()` will be non-null, and the final result WILL be saved to the database:
   ```java
   if (submission.getUserId() != null) {
       submissionRepository.save(submission);  // persists stdout, verdict, etc.
   }
   ```
   This means authenticated users can later retrieve their submission via `GET /api/v1/submissions/{id}`.

6. **GET endpoint:** Authenticated users can fetch their results:
   ```bash
   curl http://localhost:8080/api/v1/submissions/a1b2c3d4-e5f6-7890-abcd-ef1234567890 \
     -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIs..."
   ```
   This endpoint requires authentication (`SecurityConfig`: `.requestMatchers(HttpMethod.GET, "/api/v1/submissions/**").authenticated()`).

---

## 4. Sequence Diagram

```mermaid
sequenceDiagram
    participant Client
    participant RateLimitFilter
    participant SecurityFilter
    participant SubmissionController
    participant SubmissionService
    participant PostgreSQL
    participant RabbitMQ
    participant ExecutionWorker
    participant DockerSandboxManager
    participant DockerContainer
    participant WebSocket

    Client->>RateLimitFilter: POST /api/v1/submissions (JSON body)
    RateLimitFilter->>RateLimitFilter: resolveKey() -> "guest:127.0.0.1"
    RateLimitFilter->>RateLimitFilter: bucket.tryConsume(1) -> true
    RateLimitFilter->>SecurityFilter: doFilter()
    SecurityFilter->>SecurityFilter: POST /api/v1/submissions -> permitAll()
    SecurityFilter->>SubmissionController: submit(request, jwt=null)
    SubmissionController->>SubmissionService: submit(request, userId=null)
    SubmissionService->>PostgreSQL: INSERT INTO submissions (id, status=QUEUED, verdict=PENDING, ...)
    PostgreSQL-->>SubmissionService: saved (id=a1b2c3d4...)
    SubmissionService->>RabbitMQ: publish("coderank.submissions", "a1b2c3d4...")
    SubmissionService-->>SubmissionController: Submission entity
    SubmissionController-->>Client: 202 Accepted (JSON with id + wsChannel)

    Note over Client,WebSocket: Client opens WebSocket and subscribes

    Client->>WebSocket: CONNECT ws://localhost:8080/ws/execution
    Client->>WebSocket: SUBSCRIBE /topic/submissions/a1b2c3d4...

    Note over RabbitMQ,ExecutionWorker: Async processing begins

    RabbitMQ->>ExecutionWorker: "a1b2c3d4..." (message delivered)
    ExecutionWorker->>PostgreSQL: SELECT * FROM submissions WHERE id = 'a1b2c3d4...'
    PostgreSQL-->>ExecutionWorker: Submission (status=QUEUED)

    ExecutionWorker->>PostgreSQL: UPDATE status=COMPILING
    ExecutionWorker->>WebSocket: {status: COMPILING, verdict: PENDING}
    WebSocket-->>Client: STOMP MESSAGE (COMPILING)

    ExecutionWorker->>PostgreSQL: UPDATE status=RUNNING
    ExecutionWorker->>WebSocket: {status: RUNNING, verdict: PENDING}
    WebSocket-->>Client: STOMP MESSAGE (RUNNING)

    ExecutionWorker->>DockerSandboxManager: execute(submission)
    DockerSandboxManager->>DockerSandboxManager: Create /tmp/coderank-xxxxx/Main.java
    DockerSandboxManager->>DockerContainer: createContainer(coderank-sandbox-java)
    DockerSandboxManager->>DockerContainer: startContainer()

    Note over DockerContainer: entrypoint.sh runs
    DockerContainer->>DockerContainer: javac Main.java (exit 0)
    DockerContainer->>DockerContainer: java Main -> "Hello CodeRank!"
    DockerContainer-->>DockerSandboxManager: exit code 0

    DockerSandboxManager->>DockerContainer: collectLogs(stdout) -> "Hello CodeRank!\n"
    DockerSandboxManager->>DockerContainer: collectLogs(stderr) -> null
    DockerSandboxManager->>DockerSandboxManager: verdict = ACCEPTED
    DockerSandboxManager->>DockerContainer: removeContainer(force=true)
    DockerSandboxManager->>DockerSandboxManager: delete /tmp/coderank-xxxxx/
    DockerSandboxManager-->>ExecutionWorker: ExecutionResult(ACCEPTED, "Hello CodeRank!\n", ...)

    ExecutionWorker->>ExecutionWorker: userId == null -> skip DB save
    ExecutionWorker->>WebSocket: {status: COMPLETED, verdict: ACCEPTED, stdout: "Hello CodeRank!\n"}
    WebSocket-->>Client: STOMP MESSAGE (COMPLETED + result)
```

---

## 5. Data Flow Summary

This section shows what the data looks like at each stage of the pipeline.

### Stage 1: HTTP Request (raw JSON)

```json
{
  "language": "JAVA",
  "sourceCode": "public class Main { public static void main(String[] args) { System.out.println(\"Hello CodeRank!\"); } }"
}
```

### Stage 2: SubmissionRequest (Java record)

```
SubmissionRequest[
  language   = Language.JAVA
  sourceCode = "public class Main { public static void main(String[] args) { System.out.println(\"Hello CodeRank!\"); } }"
  stdin      = null
]
```

### Stage 3: Submission entity (JPA, after save)

```
Submission {
  id              = a1b2c3d4-e5f6-7890-abcd-ef1234567890   (UUID, generated)
  userId          = null                                      (guest)
  language        = JAVA
  sourceCode      = "public class Main { ... }"
  stdin           = null
  status          = QUEUED
  verdict         = PENDING
  stdout          = null
  stderr          = null
  executionTimeMs = null
  memoryUsedKb    = null
  errorMessage    = null
  createdAt       = 2026-05-24T10:12:45.231Z
  updatedAt       = 2026-05-24T10:12:45.231Z
}
```

### Stage 4: RabbitMQ message (String)

```
"a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

Published to queue: `coderank.submissions` (durable).

### Stage 5: Docker container (files on disk)

**Host side:**
```
/tmp/coderank-8472619305213/
  Main.java    ->  "public class Main { public static void main(String[] args) { System.out.println(\"Hello CodeRank!\"); } }"
```

**Container side (after compilation):**
```
/workspace/           (bind mount, read from host)
  Main.java
/tmp/out/             (writable tmpfs inside container)
  Main.class
```

### Stage 6: ExecutionResult (Java record, returned from sandbox)

```
ExecutionResult[
  verdict         = ACCEPTED
  stdout          = "Hello CodeRank!\n"
  stderr          = null
  executionTimeMs = 823
  memoryUsedKb    = null
  errorMessage    = null
]
```

### Stage 7: HTTP Response (JSON, 202 Accepted)

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "QUEUED",
  "verdict": "PENDING",
  "stdout": null,
  "stderr": null,
  "executionTimeMs": null,
  "memoryUsedKb": null,
  "errorMessage": null,
  "wsChannel": "/topic/submissions/a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "createdAt": "2026-05-24T10:12:45.231Z"
}
```

### Stage 8: ExecutionStatusMessage (WebSocket payloads, in order)

**Message 1 -- COMPILING:**
```json
{
  "submissionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPILING",
  "verdict": "PENDING",
  "stdout": null,
  "stderr": null,
  "executionTimeMs": null,
  "memoryUsedKb": null,
  "errorMessage": null,
  "timestamp": "2026-05-24T10:12:46.102Z"
}
```

**Message 2 -- RUNNING:**
```json
{
  "submissionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "RUNNING",
  "verdict": "PENDING",
  "stdout": null,
  "stderr": null,
  "executionTimeMs": null,
  "memoryUsedKb": null,
  "errorMessage": null,
  "timestamp": "2026-05-24T10:12:46.118Z"
}
```

**Message 3 -- COMPLETED (final):**
```json
{
  "submissionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPLETED",
  "verdict": "ACCEPTED",
  "stdout": "Hello CodeRank!\n",
  "stderr": null,
  "executionTimeMs": 823,
  "memoryUsedKb": null,
  "errorMessage": null,
  "timestamp": "2026-05-24T10:12:48.053Z"
}
```
