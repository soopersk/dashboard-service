# Observability Service

---

# Local Development Setup

This guide explains how to run the **Observability Service** locally using:

* Docker Compose
* Rancher Desktop
* PostgreSQL
* Redis

The application connects to **Azure PostgreSQL and Azure Redis in higher environments**, but runs against **local containers during development**.

---

## Rancher Desktop

* Start Rancher
* Verify installation:

```bash
docker --version
docker compose version
```

---

# Running Local Infrastructure

Start containers: (This will start postgres and redis)

```bash
docker compose up -d
```

Verify:

```bash
docker ps
```

You should see:

* `observability-postgres`
* `observability-redis`

---

# Local Spring Profile Configuration

Ensure you have:

## `application-local.yml`

---

# Running the Application Locally

### Option 1 — IntelliJ / IDE

Set environment variable:

```
SPRING_PROFILES_ACTIVE=local
```

Run the main application class.

---

### Option 2 — Command Line

```bash
export SPRING_PROFILES_ACTIVE=local
./mvn spring-boot:run
```

Or:

```bash
SPRING_PROFILES_ACTIVE=local ./mvn spring-boot:run
```

---

# Database & Redis Testing

## Connect to PostgreSQL

```bash
docker exec -it observability-postgres psql -U postgres -d observability
```

## Connect to Redis

```bash
docker exec -it observability-redis redis-cli
```

Test Redis:

```bash
SET test hello
GET test
```

---

# Running Integration Tests Locally

Ensure:

* Docker containers are running
* `SPRING_PROFILES_ACTIVE=local`

Run:

```bash
./mvn clean test
```

---

# Stopping Containers

```bash
docker compose down
```

To remove volumes:

```bash
docker compose down -v
```
---

# Developer Workflow

1. Start Rancher Desktop
2. Start Docker containers (postgres, redis)
3. Run application with `local` profile
4. Use Swagger UI:

   ```
   http://localhost:8080/swagger-ui.html
   ```
5. Check health endpoint:

   ```
   http://localhost:8080/actuator/health
   ```
---

