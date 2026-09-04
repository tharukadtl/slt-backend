# SLT Field Operations System — Backend (`fieldops`)

Spring Boot 3 / Java 17 REST API.

## Local development setup

`application.yml` requires these as env vars with **no insecure fallback**
baked in (previously it shipped a real JWT secret and `root`/`1234` DB
credentials as defaults — anyone reading the file could forge tokens or
guess the local DB password):

| Env var | Purpose |
|---|---|
| `JWT_SECRET` | Signing key for access/refresh tokens |
| `SPRING_DATASOURCE_USERNAME` | MySQL username |
| `SPRING_DATASOURCE_PASSWORD` | MySQL password |

### Option A — IntelliJ (already set up)

The committed `Spring Boot.Application` run configuration has its
**Active Profiles** field set to `local`. That activates
`src/main/resources/application-local.yml`, a git-ignored file carrying the
real local dev values — no env vars need to be set by hand. If you're
setting up a fresh checkout, create `application-local.yml` yourself (it's
git-ignored, so it won't already be there) with:

```yaml
spring:
  datasource:
    username: root
    password: <your local MySQL password>

app:
  jwt:
    secret: <any long random string — see note below>
```

### Option B — any other IDE / terminal / CI

Set the three env vars directly before running, e.g.:

```bash
export JWT_SECRET="$(openssl rand -hex 32)"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=1234
./mvnw spring-boot:run
```

`JWT_SECRET` just needs to be long and stable for the life of a running
instance (tokens signed with it stop validating if it changes) — generate
one with `openssl rand -hex 32` or similar, it doesn't need to be memorable.

**Never commit a real secret value** into `application.yml` or any tracked
file — `application-local.yml` and `.env` are git-ignored for exactly this
reason.
