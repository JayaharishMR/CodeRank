package com.coderank.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configures the Docker client used to spin up sandboxed containers for
 * code execution.
 *
 * A shared singleton client with a pooled HTTP connection is used so that
 * concurrent submissions reuse connections to the Docker daemon rather
 * than opening a new socket per container lifecycle call.
 */
@Configuration
public class DockerConfig {

    @Bean
    public DockerClient dockerClient() {
        // Picks up DOCKER_HOST from the environment (defaults to the local
        // Unix socket), so the same code works in dev and in prod.
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                // 100 pooled connections to handle bursts of concurrent
                // container create/start/wait/remove cycles without blocking.
                .maxConnections(100)
                // 30s connect timeout is generous enough for a healthy daemon;
                // if it takes longer, the daemon is likely unresponsive.
                .connectionTimeout(Duration.ofSeconds(30))
                // 45s response timeout covers the container lifecycle calls
                // (pull, create, wait) which can be slower than a simple ping.
                .responseTimeout(Duration.ofSeconds(45))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
