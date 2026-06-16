# CodeRank Setup Guide

Step-by-step guide to get CodeRank running locally from a fresh clone.

---

## Prerequisites

Install the following before proceeding:

| Tool | Version | Verify |
|------|---------|--------|
| **Java JDK** | 21+ (Temurin recommended) | `java --version` |
| **Maven** | 3.9+ | `mvn --version` |
| **Node.js** | 18+ | `node --version` |
| **npm** | 9+ | `npm --version` |
| **Docker** | 24+ (daemon must be running) | `docker info` |
| **Docker Compose** | v2 (bundled with Docker Desktop) | `docker compose version` |
| **Git** | Any recent version | `git --version` |

### Platform-Specific Installation

**macOS:**
```bash
brew install openjdk@21 maven node docker
```

**Ubuntu/Debian:**
```bash
# Java 21
sudo apt install -y openjdk-21-jdk

# Maven
sudo apt install -y maven

# Node.js 18+ (via NodeSource)
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install -y nodejs

# Docker
sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER  # then log out and back in
```

**Windows:**
- Install [JDK 21 (Temurin)](https://adoptium.net/)
- Install [Maven](https://maven.apache.org/download.cgi) and add to PATH
- Install [Node.js](https://nodejs.org/)
- Install [Docker Desktop](https://www.docker.com/products/docker-desktop/)

---

## Step 1: Clone the Repository

```bash
git clone https://github.com/JayaharishMR/CodeRank.git
cd CodeRank/coderank
```

Verify directory structure:
```bash
ls
# Expected: coderank-api  coderank-ui  docker  docker-compose.yml  docs  README.md
```

---

## Step 2: Start Infrastructure Services

CodeRank requires four infrastructure services. Docker Compose starts all of them:

```bash
docker compose up -d
```

This starts:

| Service | Container | Host Port | Purpose |
|---------|-----------|-----------|---------|
| **PostgreSQL 16** | coderank-postgres | 5432 | Application data + Keycloak data |
| **RabbitMQ 3.13** | coderank-rabbitmq | 5673 (AMQP), 15673 (Management UI) | Submission job queue |
| **Redis 7** | coderank-redis | 6379 | Rate-limiting counters |
| **Keycloak 24** | coderank-keycloak | 9090 | OAuth2/OIDC identity provider |

Verify all containers are running:
```bash
docker compose ps
```

All four should show `Up` status. If any container exits, check logs:
```bash
docker compose logs <service-name>
```

> **Note:** PostgreSQL automatically creates two databases on first startup:
> - `coderank` — application data (submissions, problems, users)
> - `keycloak` — Keycloak identity data
>
> This is handled by `docker/init-db.sh` which runs on first container init.

### Verify Infrastructure

```bash
# PostgreSQL
psql -h localhost -U coderank -d coderank -c "SELECT 1;"
# Password: coderank

# RabbitMQ Management UI
open http://localhost:15673
# Login: coderank / coderank

# Redis
redis-cli ping
# Expected: PONG
```

---

## Step 3: Build the Sandbox Docker Image

The sandbox is a hardened Docker image that compiles and runs user-submitted code in isolation. It must be built before the backend can execute submissions.

```bash
cd docker/sandbox
docker build -t coderank-sandbox-java .
cd ../..
```

Verify the image exists:
```bash
docker images | grep coderank-sandbox-java
```

### What the Sandbox Image Contains

- **Base:** Alpine Linux + JDK 21 (Temurin) — ~200 MB
- **User:** Non-root `coderank` user with no login shell
- **Scripts:**
  - `/entrypoint.sh` — Playground mode (compile and run with stdin)
  - `/judge-entrypoint.sh` — Judge mode (compile once, run N test cases)
- **Hardened:** `wget`/`curl` removed, no network access, all capabilities dropped

---

## Step 4: Configure Keycloak

Keycloak handles user authentication. You need to create a realm, client, and test user.

### 4.1: Access Keycloak Admin Console

Open [http://localhost:9090](http://localhost:9090) and log in:
- **Username:** `admin`
- **Password:** `admin`

### 4.2: Create Realm

1. Click the dropdown in the top-left (shows "master")
2. Click **Create realm**
3. Set **Realm name** to: `coderank`
4. Click **Create**

### 4.3: Create Client

1. In the `coderank` realm, go to **Clients** → **Create client**
2. Fill in:
   - **Client type:** OpenID Connect
   - **Client ID:** `coderank-ui`
3. Click **Next**
4. Set:
   - **Client authentication:** OFF (public client)
   - **Standard flow:** ON
   - **Direct access grants:** ON
5. Click **Next**
6. Set:
   - **Valid redirect URIs:** `http://localhost:5173/*`
   - **Valid post logout redirect URIs:** `http://localhost:5173/*`
   - **Web origins:** `http://localhost:5173`
7. Click **Save**

### 4.4: Create Test User

1. Go to **Users** → **Add user**
2. Fill in:
   - **Username:** `testuser` (or any name)
   - **Email:** `test@example.com`
   - **Email verified:** ON
3. Click **Create**
4. Go to the **Credentials** tab
5. Click **Set password**
   - **Password:** `password` (or your choice)
   - **Temporary:** OFF
6. Click **Save**

> **Note:** Keycloak is optional for basic usage. You can use the Playground and submit code as a guest without Keycloak. Authentication is required only for viewing submission history.

---

## Step 5: Start the Backend

```bash
cd coderank-api
mvn spring-boot:run
```

First run downloads dependencies (~2-3 minutes). Subsequent starts are faster.

**Watch for these log lines indicating success:**

```
Flyway: Successfully applied 2 migration(s)
Tomcat started on port 8080
Started CoderankApplication in X seconds
```

Flyway runs database migrations automatically on startup. The V2 migration seeds 4 practice problems (Two Sum, FizzBuzz, Palindrome Check, Reverse String).

### Verify Backend

```bash
# Health check
curl http://localhost:8080/api/v1/health
# Expected: {"status":"UP"}

# List problems
curl http://localhost:8080/api/v1/problems | python3 -m json.tool
# Expected: 4 problems with test cases
```

> **Troubleshooting:**
> - `Connection refused on 5432` — PostgreSQL not running. Run `docker compose up -d`
> - `Connection refused on 5673` — RabbitMQ not running. Run `docker compose up -d`
> - `Flyway migration failed` — Database might have stale state. Reset: `docker compose down -v && docker compose up -d`
> - `Cannot connect to Docker daemon` — Docker not running or permission denied. Check `docker info`

---

## Step 6: Start the Frontend

Open a **new terminal** (keep the backend running):

```bash
cd coderank-ui
npm install    # First time only — installs dependencies
npm run dev
```

**Expected output:**
```
VITE v5.x.x  ready in Xms

➜  Local:   http://localhost:5173/
```

### Verify Frontend

Open [http://localhost:5173](http://localhost:5173) in your browser. You should see the CodeRank landing page.

### Frontend Environment Variables

The frontend uses a `.env` file (already configured for local development):

```
VITE_API_BASE_URL=/api/v1
VITE_WS_URL=ws://localhost:5173/ws/execution
VITE_KEYCLOAK_URL=http://localhost:9090
VITE_KEYCLOAK_REALM=coderank
VITE_KEYCLOAK_CLIENT_ID=coderank-ui
```

The Vite dev server proxies `/api` and `/ws` requests to `http://localhost:8080`, so the frontend and backend appear to run on the same origin.

---

## Step 7: Verify End-to-End

### Test Playground Mode

1. Go to [http://localhost:5173/playground](http://localhost:5173/playground)
2. The default code prints "Hello, CodeRank!"
3. Click **Submit**
4. Watch the status stepper: QUEUED → COMPILING → RUNNING → COMPLETED
5. You should see `Hello, CodeRank!` in the stdout output

### Test Judge Mode

1. Go to [http://localhost:5173/problems](http://localhost:5173/problems)
2. Click on **FizzBuzz** (easiest to verify)
3. Write a solution and click **Submit**
4. You should see test case results: "Passed X/Y test cases" with per-case verdicts

### Test Authentication (Optional)

1. Click **Login** in the navbar
2. Enter the test user credentials from Step 4.4
3. After login, go to **Submissions** to see your submission history

---

## Step 8: Enable AI Feedback (Optional)

AI feedback provides on-demand code review for completed submissions. It requires an API key from OpenAI or any OpenAI-compatible provider.

### Option A: OpenAI

1. Get an API key from [platform.openai.com/api-keys](https://platform.openai.com/api-keys)
2. Ensure billing is set up (free trial or paid plan)
3. Set environment variables and restart the backend:

```bash
export AI_API_KEY=sk-your-openai-key-here
export CODERANK_AI_ENABLED=true
cd coderank-api
mvn spring-boot:run
```

### Option B: Google Gemini (Free Tier Available)

1. Get an API key from [aistudio.google.com/apikey](https://aistudio.google.com/apikey)
2. Set environment variables:

```bash
export AI_API_KEY=your-gemini-key-here
export AI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
export AI_MODEL=gemini-2.0-flash
export CODERANK_AI_ENABLED=true
cd coderank-api
mvn spring-boot:run
```

### Option C: Any OpenAI-Compatible API (Ollama, LiteLLM, etc.)

```bash
export AI_API_KEY=your-key       # or "ollama" for local Ollama
export AI_BASE_URL=http://localhost:11434/v1  # your provider's URL
export AI_MODEL=llama3           # your model name
export CODERANK_AI_ENABLED=true
```

### Verify AI Feedback

1. Submit a solution to any problem
2. After verdict appears, click **Get AI Feedback**
3. A loading spinner shows while the AI generates feedback (~5-10 seconds)
4. Feedback renders as formatted markdown with code analysis

### AI Configuration Reference

| Env Variable | Default | Description |
|-------------|---------|-------------|
| `CODERANK_AI_ENABLED` | `false` | Set to `true` to enable AI feedback |
| `AI_API_KEY` | — | API key for your AI provider |
| `AI_MODEL` | `gpt-4o-mini` | Model name |
| `AI_BASE_URL` | `https://api.openai.com/v1` | API base URL |

These map to `coderank.ai.*` properties in `application.yml`. Additional settings (max-tokens, temperature) can be changed in that file.

---

## Port Reference

| Port | Service | Notes |
|------|---------|-------|
| 5173 | Frontend (Vite dev server) | Proxies /api and /ws to 8080 |
| 8080 | Backend (Spring Boot) | REST API + WebSocket |
| 5432 | PostgreSQL | Default Postgres port |
| 5673 | RabbitMQ (AMQP) | Offset from default 5672 to avoid conflicts |
| 15673 | RabbitMQ Management UI | Login: coderank / coderank |
| 6379 | Redis | Default Redis port |
| 9090 | Keycloak | Admin: admin / admin |

---

## Default Credentials

| Service | Username | Password |
|---------|----------|----------|
| PostgreSQL | `coderank` | `coderank` |
| RabbitMQ | `coderank` | `coderank` |
| Keycloak Admin | `admin` | `admin` |

> **Warning:** These are development-only credentials. Never use these in production.

---

## Common Issues

### Docker daemon not running
```
Cannot connect to the Docker daemon at unix:///var/run/docker.sock
```
**Fix:** Start Docker. On Linux: `sudo systemctl start docker`. On macOS/Windows: launch Docker Desktop.

### Port already in use
```
Bind for 0.0.0.0:5432 failed: port is already allocated
```
**Fix:** Another service is using the port. Either stop it or change the port mapping in `docker-compose.yml`.

### Sandbox image not found
```
com.github.dockerjava.api.exception.NotFoundException: Status 404: No such image: coderank-sandbox-java
```
**Fix:** Build the sandbox image (Step 3): `docker build -t coderank-sandbox-java docker/sandbox/`

### Flyway migration checksum mismatch
```
FlywayValidateException: Validate failed: Detected resolved migration not applied to database
```
**Fix:** Reset the database: `docker compose down -v && docker compose up -d` then restart the backend. The `-v` flag deletes the Postgres volume, so all data is lost.

### Keycloak realm not found
```
Could not obtain discovery document from http://localhost:9090/realms/coderank
```
**Fix:** Create the `coderank` realm in Keycloak (Step 4.2). Or if you don't need authentication, the app still works for guest submissions — this error is non-fatal.

### Out of memory during Maven build
```
java.lang.OutOfMemoryError: Java heap space
```
**Fix:** Increase Maven memory: `export MAVEN_OPTS="-Xmx1024m"` then retry.

### WebSocket connection refused
The browser console shows WebSocket connection errors.
**Fix:** Ensure the backend is running on port 8080. The Vite proxy forwards `/ws` to the backend.

---

## Stopping Everything

```bash
# Stop the backend: Ctrl+C in the backend terminal
# Stop the frontend: Ctrl+C in the frontend terminal

# Stop infrastructure
docker compose down

# Stop infrastructure AND delete all data (clean slate)
docker compose down -v
```

---

## Quick Start (TL;DR)

```bash
# 1. Clone
git clone https://github.com/JayaharishMR/CodeRank.git
cd CodeRank/coderank

# 2. Infrastructure
docker compose up -d

# 3. Sandbox image
docker build -t coderank-sandbox-java docker/sandbox/

# 4. Backend (Terminal 1)
cd coderank-api && mvn spring-boot:run

# 5. Frontend (Terminal 2)
cd coderank-ui && npm install && npm run dev

# 6. Open browser
open http://localhost:5173
```

Keycloak setup (Step 4) is optional — you can submit code as a guest immediately.
