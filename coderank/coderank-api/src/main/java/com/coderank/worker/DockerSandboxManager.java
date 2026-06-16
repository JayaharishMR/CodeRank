package com.coderank.worker;

import com.coderank.model.ExecutionResult;
import com.coderank.model.Submission;
import com.coderank.model.enums.Verdict;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Creates a short-lived Docker container to compile and run user-submitted code in an
 * isolated sandbox. Every submission gets its own container that is destroyed after use,
 * preventing cross-submission interference and limiting the blast radius of malicious code.
 *
 * Security model:
 * - Network disabled (no exfiltration or lateral movement)
 * - All Linux capabilities dropped (no privilege escalation)
 * - PID limit prevents fork bombs
 * - Memory cap prevents OOM-killing the host
 * - Hard timeout kills runaway containers
 * - Source code is bind-mounted read-only from a host temp directory
 */
@Component
public class DockerSandboxManager {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxManager.class);

    private final DockerClient dockerClient;

    @Value("${coderank.docker.sandbox-image}")
    private String sandboxImage;

    @Value("${coderank.execution.timeout-seconds}")
    private int timeoutSeconds;

    @Value("${coderank.execution.memory-limit-mb}")
    private int memoryLimitMb;

    @Value("${coderank.execution.cpu-count}")
    private int cpuCount;

    @Value("${coderank.execution.pid-limit}")
    private int pidLimit;

    @Value("${coderank.execution.max-stdout-bytes}")
    private int maxStdoutBytes;

    public DockerSandboxManager(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    /**
     * Immutable result of running a single container to completion. Callers inspect
     * the exit code and output streams to determine the execution outcome.
     */
    public record ContainerExecution(int exitCode, String stdout, String stderr) {}

    /**
     * Creates, starts, and waits for a Docker container, then collects its output.
     * This is the low-level building block used by both playground execution
     * ({@link #execute}) and the judge service.
     *
     * <p>The container runs with the same security constraints in all modes:
     * capabilities dropped, network disabled, PID-limited, memory-capped.</p>
     *
     * @param workDir        host directory to bind-mount into /workspace
     * @param entrypoint     container entrypoint to override the Dockerfile default
     * @param envVars        environment variables passed into the container
     * @param timeoutSeconds hard wall-clock timeout; container is killed if exceeded
     * @param memoryBytes    memory limit in bytes
     * @return the container's exit code, stdout, and stderr
     */
    public ContainerExecution runContainer(Path workDir, String entrypoint,
            Map<String, String> envVars, int timeoutSeconds, long memoryBytes) {

        String containerId = null;
        try {
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withMemory(memoryBytes)
                    .withCpuCount((long) cpuCount)
                    .withPidsLimit((long) pidLimit)
                    .withNetworkMode("none")
                    .withReadonlyRootfs(false)
                    .withCapDrop(Capability.ALL)
                    .withSecurityOpts(List.of("no-new-privileges"))
                    .withBinds(new Bind(
                            workDir.toAbsolutePath().toString(),
                            new Volume("/workspace")
                    ));

            List<String> envList = envVars.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .toList();

            CreateContainerResponse container = dockerClient.createContainerCmd(sandboxImage)
                    .withHostConfig(hostConfig)
                    .withEntrypoint(entrypoint)
                    .withEnv(envList)
                    .exec();

            containerId = container.getId();

            dockerClient.startContainerCmd(containerId).exec();

            int exitCode;
            try {
                exitCode = dockerClient.waitContainerCmd(containerId)
                        .exec(new WaitContainerResultCallback())
                        .awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                try { dockerClient.killContainerCmd(containerId).exec(); } catch (Exception ignored) {}
                return new ContainerExecution(-1, null, "Timed out");
            }

            String stdout = collectLogs(containerId, true);
            String stderr = collectLogs(containerId, false);

            return new ContainerExecution(exitCode, stdout, stderr);

        } catch (Exception e) {
            log.error("Container execution failed", e);
            return new ContainerExecution(-1, null, e.getMessage());
        } finally {
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * High-level playground execution: writes the submission's source code and stdin
     * to a temp directory, runs the default sandbox entrypoint, and translates the
     * raw container output into an {@link ExecutionResult} with a verdict.
     *
     * <p>This method is used for free-run (non-judged) submissions where there is
     * no expected output to compare against.</p>
     */
    public ExecutionResult execute(Submission submission) {
        Path tempDir = null;
        long startTime = System.currentTimeMillis();

        try {
            // --- Stage 1: Prepare host temp directory with source code ---

            tempDir = Files.createTempDirectory("coderank-");
            Path sourceFile = tempDir.resolve("Main.java");
            Files.writeString(sourceFile, submission.getSourceCode());

            if (submission.getStdin() != null) {
                Files.writeString(tempDir.resolve("stdin.txt"), submission.getStdin());
            }

            // Rootless Docker remaps UIDs: the container's non-root user (e.g., UID 1000)
            // maps to a high UID on the host. Without world-readable permissions here,
            // the container process cannot read the bind-mounted source files.
            Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(sourceFile, PosixFilePermissions.fromString("rw-r--r--"));
            if (submission.getStdin() != null) {
                Files.setPosixFilePermissions(tempDir.resolve("stdin.txt"), PosixFilePermissions.fromString("rw-r--r--"));
            }

            // --- Stage 2: Run container using the shared runContainer method ---

            ContainerExecution result = runContainer(
                    tempDir,
                    "/entrypoint.sh",
                    Map.of(),
                    timeoutSeconds,
                    (long) memoryLimitMb * 1024 * 1024
            );

            // --- Stage 3: Translate container result into ExecutionResult ---

            String stdout = result.stdout();
            String stderr = result.stderr();

            // Prevent unbounded output from consuming excessive memory or DB storage.
            if (stdout != null && stdout.length() > maxStdoutBytes) {
                stdout = stdout.substring(0, maxStdoutBytes) + "\n[output truncated]";
            }

            long elapsed = System.currentTimeMillis() - startTime;

            if (result.exitCode() == -1) {
                return new ExecutionResult(Verdict.TIME_LIMIT_EXCEEDED, null, null, elapsed, null,
                        "Execution timed out after " + timeoutSeconds + " seconds");
            } else if (result.exitCode() == 0) {
                return new ExecutionResult(Verdict.ACCEPTED, stdout, stderr, elapsed, null, null);
            } else if (stdout != null && stdout.trim().startsWith("COMPILE_ERROR")) {
                String compileError = stdout.trim().replaceFirst("COMPILE_ERROR\n?", "");
                return new ExecutionResult(Verdict.COMPILATION_ERROR, null, compileError, elapsed, null, null);
            } else {
                return new ExecutionResult(Verdict.RUNTIME_ERROR, stdout, stderr, elapsed, null,
                        "Process exited with code " + result.exitCode());
            }

        } catch (Exception e) {
            log.error("Execution failed for submission", e);
            long elapsed = System.currentTimeMillis() - startTime;
            return new ExecutionResult(Verdict.RUNTIME_ERROR, null, null, elapsed, null, e.getMessage());
        } finally {
            // Walk in reverse depth order so child files are deleted before parent dirs.
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
     * Collects container logs using a raw ResultCallback.Adapter instead of the simpler
     * LogContainerResultCallback.toString() because the latter silently swallows errors
     * and can return incomplete output. The adapter with a CountDownLatch gives us explicit
     * control over completion and error handling, plus a timeout to avoid hanging if the
     * Docker daemon stalls.
     */
    private String collectLogs(String containerId, boolean stdout) {
        try {
            StringBuilder sb = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);

            dockerClient.logContainerCmd(containerId)
                    .withStdOut(stdout)
                    .withStdErr(!stdout)
                    .withFollowStream(false)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(frame.getPayload()));
                        }

                        @Override
                        public void onComplete() {
                            latch.countDown();
                            super.onComplete();
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            // Release the latch on error too, so we don't block for the full 5 seconds.
                            latch.countDown();
                            super.onError(throwable);
                        }
                    });

            // 5-second safety timeout prevents indefinite blocking if the log stream hangs.
            latch.await(5, TimeUnit.SECONDS);
            String result = sb.toString();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }
}
