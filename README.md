# billit

## Skills

- Kotlin 2.3.21, Java 21
- Spring Boot 4.1.1, Spring Data JPA
- PostgreSQL 17, Redis 7.4, S3 호환 오브젝트 스토리지(Oracle Object Storage)
- Flyway
- OpenAPI, Swagger UI
- Kotest, Testcontainers
- Gradle Kotlin DSL

## local 실행

### 1. JDK 21

- SDKMAN

```bash
sdk env install
sdk env
```

- Local JDK

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```


### 2. PostgreSQL, Redis, S3 API

S3 API를 지원하는 오브젝트 스토리지 모두 가능

```bash
docker run -d --name billit-postgres \
  -p 127.0.0.1:5432:5432 \
  -e POSTGRES_DB=billit \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -v billit-postgres-data:/var/lib/postgresql/data \
  postgres:17-alpine
docker run --rm -d --name billit-redis \
  -p 127.0.0.1:6379:6379 redis:7.4-alpine
```

### 3. .env 설정

`.env.example` 참고해서 원하는 설정값 구성 후 root에 `.env` 생성

### 4. SpringBoot 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun \
  --args='--spring.config.import=file:./.env[.properties]'
```

### 5. API 확인

실행 후 [Swagger UI](http://localhost:8080/swagger-ui/index.html)에서 API 확인하며 테스트 진행
