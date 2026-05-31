# Alpine-based Temurin image keeps the sandbox small (~200 MB) while
# providing a full JDK 21 needed to compile user-submitted code.
FROM eclipse-temurin:21-jdk-alpine

# Non-root user with no login shell (/bin/false) so submitted code
# cannot escalate privileges or start an interactive session.
RUN adduser -D -s /bin/false coderank && \
    mkdir -p /workspace && \
    chown coderank:coderank /workspace

# bash is needed by entrypoint.sh (Alpine ships only busybox sh).
# wget/curl are removed to prevent user code from making outbound
# network requests; Docker network restrictions handle the rest.
RUN apk --no-cache add bash && \
    rm -rf /usr/bin/wget /usr/bin/curl 2>/dev/null || true

COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh

# Judge entrypoint handles multi-test-case evaluation (compile once,
# run N times). The host overrides the default entrypoint at container
# creation time via Docker API when running in judge mode.
COPY judge-entrypoint.sh /judge-entrypoint.sh
RUN chmod 755 /judge-entrypoint.sh

# Drop to the unprivileged user before any user code runs.
USER coderank
WORKDIR /workspace

ENTRYPOINT ["/entrypoint.sh"]
