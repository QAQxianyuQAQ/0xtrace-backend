# AGENTS.md

> This document defines the authoritative engineering contract for AI coding agents working on this repository. All agents MUST read this file in full before generating, modifying, or reviewing any code. When conflicts arise between this document and ad-hoc instructions, this document wins unless a human maintainer explicitly overrides it.

---

## 1. Project Identity

| Field | Value |
| --- | --- |
| Project Name | CTF Competition Platform |
| Owner | Cybersecurity Group, Student Association for Science & Technology, School of Computer Science, Nanjing University of Posts and Telecommunications (NJUPT) |
| Domain | Capture-The-Flag (CTF) competition hosting, challenge delivery, scoring, and team management |
| Architecture | **Monolithic** application, strictly layered MVC |
| Backend Language | **Java 25** |
| Framework | **Spring Boot 3.x** (aligned with Java 25) |
| Primary Datastore | **MySQL** |
| Cache Layer | **Redis** (challenges, competition metadata, leaderboards, sessions) |
| Object Storage | **MinIO** (attachments, challenge files, avatars) |
| Auth | **Spring Security + JWT** |
| Deployment | **Single-host Docker** (Docker Compose) |
| CI/CD | Automated pipeline (build → test → image → deploy) |
| Root Package | `com.qaqxianyuqaq.backend` |

---

## 2. Non-Negotiable Rules

Agents MUST comply with every rule below. Violations are treated as build failures.

1. **Java version**: All source and build configuration MUST target **Java 25**. Do not downgrade language features or toolchain settings.
2. **Comment language**: **ALL inline comments, Javadoc, and code-level documentation MUST be written in Simplified Chinese (中文)**. Class names, method names, variables, and identifiers remain in English. Commit messages and this document are in English.
3. **MVC strictness**: The project MUST follow the classic MVC layering. Cross-layer shortcuts (e.g., Controller calling a Mapper/Repository directly) are forbidden.
4. **Service layer annotation discipline**: Every business-logic step inside a Service implementation MUST be annotated with a Chinese comment describing *what* that step does and *why*, immediately above the relevant statement or block.
5. **Non-business logic extraction**: Anything that is not core business logic (timing, logging, retries, metrics, transactions wrappers, idempotency guards, permission checks that are cross-cutting, etc.) MUST be extracted into either:
   - a dedicated utility method/class, or
   - an AOP aspect.
   It MUST NOT be inlined into the Service business flow.
6. **Utility classes**: All reusable helpers MUST live under `util`.
7. **Domain entities**: The entity/model folder MUST be named `domain`.
8. **SQL DDL**: All `CREATE TABLE`, index, and migration scripts MUST live in `src/main/resources/sql/`.
9. **API prefix**: Every HTTP endpoint MUST begin with `/api/v1/`. No exceptions, including health checks and internal endpoints.
10. **Auth**: Login and protected routes MUST use Spring Security + JWT. Do not invent a parallel auth mechanism.
11. **File storage**: All binary/large-object persistence MUST go through MinIO. Do not write files to the local filesystem in production code paths.
12. **Challenge containers**: Docker-backed challenges MUST run on the single supported Docker Engine, inject team-scoped flags through the configured environment variable, and connect clients through the authenticated TCP-over-WebSocket gateway. Never expose internal container IPs or raw host ports as public connection targets. Each team may own at most one running challenge container; containers expire after one hour and may only be extended by one hour while 30 minutes or less remain.
13. **Flag ownership**: A Docker challenge submission MUST belong to the submitting team. A non-owner flag MUST be logged as a suspected collusion attempt and rejected with `FlagOwnerException`.

---

## 3. Package & Directory Layout

Agents MUST place new code according to this tree. Creating new top-level packages requires maintainer approval.

```
com.qaqxianyuqaq.backend
├── config          // Spring, Security, Redis, MinIO, Docker runtime configuration
├── controller      // MVC "C" — HTTP boundary only
├── service         // Business logic interfaces
│   └── impl        // Business logic implementations (step-wise CN comments required)
├── domain          // Entities, DTOs, VOs, enums
├── repository      // Persistence interfaces (Spring Data JPA / MyBatis mappers)
├── aspect          // AOP aspects (logging, audit, rate-limit, transaction hints)
├── util            // Stateless helper utilities
├── exception       // Custom exceptions + global exception handler
└── security        // JWT filters, providers, UserDetails impl, permission evaluators
```

Resource layout:

```
src/main/resources
├── application.yml
├── application-{profile}.yml
└── sql/            // All DDL and seed scripts, ordered and versioned
```

---

## 4. Layer Responsibilities

| Layer | Allowed To Do | Forbidden To Do |
| --- | --- | --- |
| `controller` | Parse request, validate input (Bean Validation), call Service, map to response DTO | Contain business logic, access repositories directly, format raw SQL |
| `service` | Orchestrate business rules, coordinate repositories, publish events | Know about HTTP types (`HttpServletRequest`, `ResponseEntity`), build transport DTOs |
| `repository` | Query/persist via ORM | Contain business branching logic |
| `domain` | Hold state and shape contracts | Contain I/O, contain framework annotations beyond validation/serialization |
| `util` | Pure functions, static helpers | Hold mutable state, depend on Spring context |
| `aspect` | Cross-cutting concerns | Encode core domain rules |

---

## 5. Coding Conventions

### 5.1 Comments (Chinese, mandatory in Service)

```java
@Service
public class ChallengeServiceImpl implements ChallengeService {

    @Override
    public ChallengeVO publishChallenge(ChallengeCreateDTO dto, Long operatorId) {
        // 1. 校验操作者是否具备发布题目的权限
        permissionEvaluator.assertCanPublish(operatorId);

        // 2. 校验题目基础字段的合法性（名称、分类、难度、分值）
        challengeValidator.validateForPublish(dto);

        // 3. 将题目实体落库，生成主键
        Challenge entity = ChallengeMapper.toEntity(dto);
        challengeRepository.save(entity);

        // 4. 写入 Redis 缓存，加速竞赛期间的题目读取
        challengeCache.put(entity.getId(), entity);

        // 5. 记录审计日志，便于赛事复盘
        auditPublisher.publishChallengeCreated(entity.getId(), operatorId);

        // 6. 返回给上层用于响应的视图对象
        return ChallengeMapper.toVO(entity);
    }
}
```

Rules:
- Number each business step.
- One Chinese sentence per step, explaining intent, not syntax.
- Comment above the statement/block it describes.

### 5.2 Naming
- Classes: `PascalCase`, English, domain-meaningful (`ChallengeServiceImpl`, not `ChallSvc`).
- Methods/fields: `camelCase`, English.
- Constants: `UPPER_SNAKE_CASE`.
- DTOs: suffix `DTO`, `VO`, `Request`, `Response` consistently within a module.

### 5.3 Cross-Cutting Concerns → AOP or Util
Examples that MUST be extracted:
- Operation logging → `aspect/OperationLogAspect`
- Audit trail → `aspect/AuditAspect`
- Rate limiting → `aspect/RateLimitAspect`
- Distributed lock → `aspect/DistributedLockAspect`
- ID generation, time formatting, JSON mapping, hashing → `util/*`

---

## 6. Persistence & Cache

- **MySQL**: Source of truth. All schema changes MUST be reflected in `resources/sql/`.
- **Redis** responsibilities:
  - Challenge payloads
  - Competition metadata
  - Leaderboard sorted sets
  - JWT blacklist / session metadata
- Cache keys MUST be centralized in a `CacheKeys` utility (in `util`) with Chinese comments describing lifetime and invalidation policy.
- Never cache raw credentials.

---

## 7. Security

- Authentication: `Spring Security` filter chain + `JWT` (HMAC or RS256, configurable).
- Token concerns (issuing, parsing, validation, refresh) MUST be encapsulated in `security` package.
- Password hashing: `BCrypt` (or `Argon2` if configured).
- Every protected endpoint MUST declare its authorization rule explicitly.
- Sensitive operations MUST pass through an audit aspect.

---

## 8. File Storage (MinIO)

- All uploads/downloads go through a `MinioUtil` (in `util`) and a dedicated service.
- Buckets MUST be declared and initialized on startup.
- Never expose MinIO credentials to the client; use presigned URLs with short TTLs.
- Do not write to `./uploads`, `target/`, or any local path in production code.

---

## 9. API Conventions

- Prefix: **`/api/v1/`** — mandatory on every endpoint.
- Request/Response: JSON, UTF-8.
- Error envelope: unified `{ code, message, data, traceId }` returned from a global exception handler.
- HTTP status codes MUST be semantically correct (200/201/204/400/401/403/404/409/422/500).
- Pagination: standard `page`, `size`, `sort` params; response includes `total`, `items`.
- Versioning is by URL segment; breaking changes require `/api/v2/`.

---

## 10. Build, Test, and CI/CD

- **Build tool**: Maven (or Gradle) pinned to Java 25 toolchain.
- **Testing**: JUnit 5 + Spring Boot Test + Testcontainers (MySQL, Redis, MinIO) — no mocks for infrastructure boundaries.
- **Coverage**: Service-layer business logic MUST have unit tests; critical paths MUST have integration tests.
- **CI pipeline stages** (must exist and be automated):
  1. Lint & static analysis
  2. Compile (Java 25)
  3. Unit tests
  4. Integration tests (Testcontainers)
  5. Build Docker image
  6. Push image to registry
  7. Deploy the single-host Docker Compose stack (production gate required)
- **Docker**: Multi-stage builds; final image based on a slim JRE 25 runtime; non-root user.
- **Runtime host**: Exactly one Docker Engine and one application replica are supported. Challenge containers join the internal runtime network and do not publish public ports.
- **Public edge**: Port 443 is reserved for the frp WSS bridge to internal challenge containers. Other services may expose independent public ports, such as REST on 8080 and MinIO on 9000.

---

## 11. Agent Workflow Requirements

When generating or editing code, agents MUST:

1. **Read this file first** and restate the constraints relevant to the task.
2. **Identify the layer** of the target change and stay inside it.
3. **Write Chinese step-wise comments** for every business step in Service implementations.
4. **Extract** any non-business concern into `util` or `aspect` before finishing.
5. **Place SQL** in `resources/sql/` if schema changes are involved.
6. **Prefix endpoints** with `/api/v1/`.
7. **Route file I/O through MinIO**, never local disk.
8. **Run through the pre-commit checklist** below before declaring the task complete.
9. **Never** introduce new frameworks, auth mechanisms, or top-level packages without maintainer approval.

---

## 12. Pre-Commit Checklist

- [ ] Java 25 toolchain honored
- [ ] All comments in Chinese; all identifiers in English
- [ ] MVC layering preserved (no cross-layer skips)
- [ ] Service business steps numbered and commented in Chinese
- [ ] Non-business logic in `util` or `aspect`
- [ ] Entities under `domain`
- [ ] DDL under `resources/sql/`
- [ ] Endpoints prefixed with `/api/v1/`
- [ ] Spring Security + JWT used for auth
- [ ] Files stored via MinIO
- [ ] CI/CD pipeline config updated if needed
- [ ] Dockerfile / Docker Compose files consistent with changes
- [ ] Tests added or updated

---

## 13. Escalation

If a requirement in a task conflicts with this document, or if a needed action is not covered here, the agent MUST:

1. Stop,
2. Summarize the conflict,
3. Ask the maintainer for explicit direction.

Silent deviations are prohibited.

---

*Last updated: maintained by the Cybersecurity Group, NJUPT. Changes require review by a project maintainer.*
