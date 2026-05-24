# CodeRank — Online Code Execution & Judging Platform

> **Execute Code Anywhere. Any Language. Zero Setup.**

## Overview

CodeRank is a self-hostable online code judging platform (LeetCode-style) that lets developers submit and evaluate code in a secure, sandboxed environment via RESTful APIs with real-time WebSocket feedback.

## The Problem

Local dev setup is inconsistent and slow. Running untrusted code safely requires complex sandboxing. Existing platforms are restrictive, expensive, or lack API-first flexibility.

## The Solution

A **containerized, queue-driven execution engine** — secure by default, scalable by design.

```
Client → Spring Boot API → RabbitMQ → Worker → Docker Sandbox
              │                                       │
         Keycloak (Auth)                     Pre-warmed Container Pool
              │
         PostgreSQL
```

## Key Features

- **Sandboxed Execution** — Docker containers: network disabled, read-only FS, PID/memory/CPU limits, dropped capabilities
- **Judge Mode** — Problems with test cases, verdicts (AC, WA, TLE, MLE, RE, CE)
- **Real-time Updates** — WebSocket status streaming (Queued → Compiling → Running → Done)
- **Guest + Registered** — Guests can execute; results persisted only for registered users
- **Configurable Limits** — Timeout (10s), memory (256MB), compile (30s), stdout (64KB), rate limits per role
- **Multi-Language Ready** — Java first, designed to scale to Python, JS, C++
- **AI Assistant (Phase 2)** — OpenAI-powered hints, code review, problem explanations, adaptive learning — available to all users

## Tech Stack

| Component | Technology | Component | Technology |
|-----------|------------|-----------|------------|
| API Server | Spring Boot | Queue | RabbitMQ |
| Auth | Keycloak (OIDC) | Database | PostgreSQL |
| Containers | Docker Java SDK | Real-time | WebSocket (STOMP) |
| Rate Limiting | Bucket4j + Redis | AI | OpenAI API |
| Deployment | Docker Compose | Extensions | Event-driven SPI |

## Roadmap

| Phase 1: Core Engine | Phase 2: Judge + AI | Phase 3: Multi-Lang & UI |
|----------------------|----------------------|--------------------------|
| REST API (Java only) | Problems/test cases CRUD | Python, JS, C++ support |
| Docker sandboxed execution | Auto-judging + verdicts | Web UI for submissions |
| Keycloak auth + guest flow | AI Assistant (OpenAI) | User dashboard + stats |
| WebSocket status updates | Hints, code review, adaptive learning | Leaderboards, contests |
| Extension point system (SPI) | Submission history/stats | |

---

**GitHub**: [Repository Link] | **Contact**: [Your Contact Info]
