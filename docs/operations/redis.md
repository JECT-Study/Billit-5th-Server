# Redis

## 사용 범위

Redis는 조회 결과 캐시에 사용합니다. 인증 데이터 저장 여부는 인증 방식에 따라 결정합니다. MQ, Pub/Sub와 Streams는 이번 구성에 포함하지 않습니다. 업무 데이터의 원본은 PostgreSQL에 저장합니다.

현재는 연결 설정만 제공합니다. 인증 데이터 저장소와 캐시 대상은 해당 기능에서 필요에 따라 추가합니다. `@EnableCaching`, 범용 Redis Repository와 공통 잠금 wrapper를 미리 만들지 않습니다.

## 연결과 데이터 계약

[환경 설정](configuration.md#redis)에 따라 Spring Boot가 제공하는 `StringRedisTemplate`을 사용합니다. 업무별 저장과 조회 코드는 해당 업무의 `infra`에서 관리하고, 키, 값의 형식과 TTL은 해당 기능에서 명시합니다. JPA Entity와 Java 객체 직렬화 결과를 Redis에 저장하지 않습니다.

키는 애플리케이션, 업무와 용도를 구분합니다. 토큰 원문이나 개인정보를 키와 로그에 넣지 않습니다. JSON을 저장하면 필요한 값만 직렬화하고 스키마 변경 시 기존 값의 호환성과 만료를 검토합니다.

Redis 저장과 PostgreSQL 변경은 하나의 원자적 트랜잭션으로 처리되지 않습니다. 두 저장소를 함께 사용하는 기능은 수행 순서와 중간 실패 시 처리를 명시합니다.

## 인증 데이터

인증 방식, 리프레시 토큰 사용 여부와 Redis 저장 대상은 인증 기능에서 결정합니다. Redis에 인증 데이터를 저장하면 저장 형식, 사용자 또는 세션별 키, TTL, 재발급과 폐기 정책을 명시합니다. Redis 오류를 토큰 검증 성공이나 유효한 세션 없음으로 임의 변환하지 않습니다.

## 조회 캐시

반복 조회가 많고 변경 빈도가 낮은 대상부터 캐시를 적용합니다. 캐시 대상, 키에 포함할 조회 조건과 권한 범위, TTL, 변경 후 무효화 시점을 기능별로 정합니다. 여행 권한과 정산 상태처럼 변경 허용 여부를 결정하는 값은 변경 요청 시 DB에서 최종 검증합니다.

원본 조회로 대체할 수 있는 캐시 실패와 인증 데이터 접근 실패는 구분합니다. PostgreSQL 커밋 전에 캐시만 갱신하거나 무효화하여 이전 값이 다시 저장되는 경로를 검토합니다.

## 지출 수정 잠금

지출 수정 잠금은 Redis를 쓰지 않고 PostgreSQL의 `expense` 행에 소유자, 토큰과 만료 시각 칼럼으로 저장합니다. 획득, 연장과 해제는 각각 조건부 UPDATE 한 번으로 처리해 경쟁하는 요청 중 하나만 성공합니다. 지출 수정은 소유자, 토큰과 만료 조건을 함께 검사하는 조건부 UPDATE로 지출을 바꾸고 같은 트랜잭션에서 잠금을 해제합니다. 조건은 [ExpenseRepository](../../src/main/kotlin/com/ject/module/expense/domain/ExpenseRepository.kt)의 잠금 메서드와 `updateExpenseIfEditLocked`를 따릅니다.

## 메모리와 장애 처리

용도별 인스턴스 분리는 전제하지 않습니다. 인증 데이터와 잠금 키는 조회 캐시와 달리 임의 삭제되면 동작에 영향을 줍니다. 같은 인스턴스에서 사용할 때는 이 키들의 만료 전 축출을 방지하도록 메모리 정책을 정합니다. 구체적인 축출 정책, 메모리 사용량 감시, 메모리 부족에 따른 저장 실패와 인증 데이터 유실 시 처리는 배포 전에 결정합니다.

## 로컬 실행

프로젝트 전용 컨테이너를 실행합니다. 다른 프로젝트 Redis를 공유하지 않습니다. 로컬 `6379` 포트를 사용 중이면 `16379` 포트를 사용하고 `REDIS_PORT=16379`로 애플리케이션을 실행합니다.

```bash
docker run --rm -d --name billit-redis -p 127.0.0.1:16379:6379 redis:7.4-alpine
REDIS_PORT=16379 SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
docker stop billit-redis
```

이 컨테이너는 개발용이며 종료 시 데이터가 유실됩니다. 배포 환경의 영속성 설정으로 사용하지 않습니다.

## 검증

실제 Redis로 값 저장, 조회, TTL, 잠금 경합과 소유권을 확인합니다. 잠금이 DB 변경을 보호하는 기능에서는 TTL 만료 후 이전 요청이 재개되는 상황과 트랜잭션 완료 순서도 검증합니다. Redis 장애 시 결과와 재시도 정책은 기능별로 검증합니다.

## 참고

- [Spring Boot Redis 지원](https://docs.spring.io/spring-boot/reference/data/nosql.html#data.nosql.redis)
- [Redis SET](https://redis.io/docs/latest/commands/set/)
- [Redis 분산 잠금과 보장 범위](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)
