# 코드 스타일

## 기본 원칙

- 입력, 조회, 변경, 트랜잭션과 외부 호출의 순서를 코드에서 드러냅니다.
- 의존성은 생성자로 주입하고 데이터와 의존성은 불변으로 선언합니다.
- helper, 인터페이스와 wrapper는 독립적인 책임이나 반복되는 변경 이유가 있을 때 추가합니다.
- 내부 계약 위반을 임의의 기본값, 광범위한 예외 처리나 성공 응답으로 숨기지 않습니다.
- 조건부 UPDATE와 DB 제약조건을 사전 조회만으로 대체하지 않습니다.
- 보안 기능과 프로토콜은 검증된 라이브러리를 사용합니다.

## 클래스와 의존성

주 생성자의 `private val`로 의존성을 주입합니다. Spring Bean은 일반 `class`, 값의 동등성이 필요한 DTO는 `data class`, 상태는 `enum class`로 선언합니다. JPA Entity를 DTO와 같은 기준으로 `data class`로 선언하지 않습니다. 모든 값을 value class로 감싸지 않습니다.

### 생성자 주입 예시

```kotlin
@Service
class TripCloser(
    private val tripCommandRepository: TripCommandRepository,
    private val clock: Clock,
) {
    @Transactional
    fun close(tripId: Long) {
        val now = clock.instant()

        tripCommandRepository.closeIfOpen(tripId, now)
    }
}
```

Spring 프록시 대상 클래스는 `kotlin-spring` 플러그인으로 엽니다. 모든 클래스에 `open`을 붙이지 않습니다. JPA Entity의 프록시와 기본 생성자는 `allOpen`과 `kotlin-jpa` 설정으로 처리합니다.

의존성과 구현 helper는 `private`로 제한합니다. `internal`은 Kotlin 컴파일 모듈 범위이므로 업무 영역 간 접근을 제한하는 수단으로 사용하지 않습니다. 운영 코드에 필드 주입용 `lateinit`을 사용하지 않습니다.

## 이름과 타입

클래스는 `UpperCamelCase`, 함수와 프로퍼티는 `lowerCamelCase`, 상수와 enum 항목은 `UPPER_SNAKE_CASE`로 작성합니다. 파일 이름은 대표 타입 이름과 맞춥니다.

의존 객체의 이름은 타입 전체를 `lowerCamelCase`로 표현합니다. `SearchTripsUseCase`는 `searchTripsUseCase`, `TripCommandRepository`는 `tripCommandRepository`로 선언합니다. `useCase`, `service`, `repository`처럼 역할만 남기지 않습니다. 같은 타입을 구분할 때는 역할을 설명하는 수식어를 붙입니다.

공개 함수와 경계에서 사용하는 함수의 반환 타입은 명시합니다. 값을 반환하지 않는 block body 함수의 `Unit`은 생략할 수 있습니다.

여러 반환값은 의미가 드러나는 타입으로 표현합니다. 의미 없는 `Pair`, `Triple`, `Map<String, Any>`나 이름만 맞추기 위한 단일 값 wrapper를 만들지 않습니다.

## 입력과 반환 계약

단순 입력은 이름과 타입이 드러나는 개별 매개변수로 전달합니다. 인자 개수나 계층 간 전달 편의만으로 입력 DTO를 만들지 않습니다. 입력 조합 자체가 함께 변경되거나 재사용되는 계약이면 변경 입력은 `*Command`, 조회 입력은 `*Query`로 표현합니다.

HTTP `*Request`, `*Response`를 유스케이스의 입력이나 반환 타입으로 사용하지 않습니다. Controller가 HTTP 입력을 업무 입력으로, 업무 결과를 HTTP 응답으로 변환합니다. 이 구분만을 위해 동일한 필드의 DTO를 양쪽에 만들지는 않습니다.

최상위 HTTP DTO와 유스케이스 입력, 결과 DTO는 별도 파일에 둡니다. JSON 내부 구조에만 의미가 있는 DTO는 중첩할 수 있지만 독립적인 업무 개념을 중첩 DTO로 숨기지 않습니다. 순수한 `from` 변환에는 DB 접근, 현재 시각 조회, 쿠키 처리와 외부 호출을 포함하지 않습니다.

타입과 패키지의 소유권은 업무 의미에 따라 정합니다. 패키지 확장과 도메인 추상화의 합의 절차는 [에이전트 작업 가이드](../../AGENTS.md#변경-범위)를 따릅니다.

## 불변성과 컬렉션

`val`을 기본으로 하고 재할당이 필요한 지역 상태에만 `var`를 사용합니다. JPA가 관리하는 변경 가능한 상태는 Entity 안에서 다룹니다.

`List`는 읽기 전용 인터페이스이며 깊은 불변을 보장하지 않습니다. 호출자의 mutable collection을 보관하면 경계에서 복사합니다. `data class.copy()`가 업무 불변식을 우회할 수 있는 모델은 공개 생성자와 복사 기능의 적합성을 검토합니다.

민감정보를 포함한 객체를 로그에 통째로 넘기지 않습니다. 로그 작성 기준은 [로깅](../operations/logging.md)을 따릅니다.

## Null과 실패

- 조회 결과 없음과 nullable 컬럼은 nullable 타입으로 표현합니다. Kotlin 내부 계약에 `Optional`을 새로 도입하지 않습니다.
- Java API가 반환하는 `Optional`은 경계에서 변환하거나 명시적으로 처리합니다.
- `!!`로 nullable 계약을 지우지 않습니다.
- 외부 입력은 경계에서 검증하고 내부의 non-null 인자를 반복 검사하지 않습니다.
- `?: return`과 `?: throw`는 종료 경로에 사용합니다. 기본값을 반환하는 Elvis 식은 그 기본값이 계약에 있을 때만 사용합니다.
- `checkNotNull`은 보장된 내부 결과의 필수 값을 확인할 때 사용하고 업무 실패를 대신하지 않습니다.
- Java 라이브러리의 platform type은 스키마와 API 계약을 확인해 경계에서 null 허용 여부를 정합니다. 컬렉션과 원소의 null 허용 여부도 구분합니다.

두 nullable 값의 관계를 판단할 때는 중첩된 `?.let`보다 조건을 명시한 `if`를 사용합니다. null과 빈 문자열을 함께 제외하면 `!isNullOrEmpty()`를 사용하고, 공백까지 제외해야 하는 입력 계약일 때만 `isNullOrBlank()`를 사용합니다.

## 조건과 실행 순서

값을 선택하는 짧은 `if` 식은 허용합니다. 업무 변경, 외부 호출과 여러 단계의 실패 처리는 block body로 작성합니다. statement 형태의 `if`는 한 줄이어도 중괄호를 사용합니다.

분기 형식 예시입니다.

```kotlin
if (!passwordMatches) {
    throw InvalidCredentialsException()
}
```

enum 분류에는 항목을 명시한 `when`을 우선 사용합니다. 모든 항목을 처리할 수 있으면 `else`를 두지 않습니다. enum 항목은 줄을 나눠 선언합니다. enum 소유권, 업무 간 참조와 로직 배치는 [아키텍처](architecture.md#enum-소유권과-참조)를 따릅니다.

검증, 조회, 업무 판단, 변경과 응답 구성의 경계는 빈 줄과 지역 변수로 구분합니다. 업무 계산과 외부 호출의 결과는 의미 있는 지역 변수로 드러냅니다. 순수 프로퍼티 접근과 DTO 구성은 불필요하게 분해하지 않습니다.

Kotlin 함수나 생성자에 같은 타입의 값이 반복돼 위치만으로 의미를 구분하기 어렵다면 named argument를 사용합니다. Java API에는 적용하지 않습니다.

private helper는 독립적인 책임과 이름이 있고 반복이나 변경 이유가 확인될 때 추출합니다. 호출부에서도 주요 조건과 부수 효과의 순서를 확인할 수 있어야 합니다.

## Scope function과 반복

`let`, `run`, `apply`, `also`, `with`를 중첩해 receiver와 실행 순서를 숨기지 않습니다. nullable 값 하나에 대한 선택적 처리는 중첩 없는 `?.let`으로 표현할 수 있습니다.

- 자원 해제에는 `use`를 사용합니다.
- 순수 컬렉션 변환에는 `map`, `filter`, `sortedBy`를 사용할 수 있습니다.
- 상태 변경, 예외와 여러 중간값이 있는 반복은 `for`를 우선합니다.
- `runCatching`으로 예상하지 못한 실패를 정상 결과로 바꾸지 않습니다.
- lazy initialization이나 I/O를 일으키는 getter를 호출하고 값을 사용하지 않는다면 실행 이유를 가까운 주석에 남깁니다.

## Spring, JPA와 JSON

Bean Validation은 `@field:NotBlank`처럼 annotation 적용 위치를 명시합니다. container element validation을 사용하면 JVM type annotation을 빌드에서 활성화합니다.

`@ConfigurationProperties`는 생성자와 `val`로 구성합니다. Bean과 기능 활성화 설정의 위치는 [환경 설정](../operations/configuration.md#기동-코드와-설정-클래스)을 따릅니다.

JSON 기본값은 입력 생략을 허용하는 계약일 때만 둡니다. 필수 입력을 역직렬화하기 위한 빈 문자열 기본값은 추가하지 않습니다. Jackson 3 Kotlin 모듈을 사용하며 Java getter 이름을 맞추기 위한 호환 메서드는 추가하지 않습니다.

`@Transactional`은 외부에서 Spring 프록시를 통해 호출되는 업무 실행 경계에 둡니다. 자기 호출로 새 트랜잭션이 생긴다고 가정하지 않습니다. JPA의 지연 로딩과 변경 감지는 트랜잭션 경계 안에서 다룹니다.

## Kotlin 기능 도입

확장 함수, 연산자 오버로딩과 DSL은 소유자와 호출 결과를 명확하게 할 때 사용합니다. 업무 변경을 getter에 숨기지 않습니다. `object`로 주입받아야 하는 협력 객체를 전역 싱글턴으로 대체하지 않습니다.

JPA와 JDBC를 사용하는 호출에 coroutine, `suspend`, `Flow`, R2DBC나 WebFlux를 문법 편의만으로 도입하지 않습니다. 비동기 경계를 도입할 때는 연결, 트랜잭션 컨텍스트 전파와 외부 실패를 별도로 설계합니다. 시각 처리는 [시간 처리](../operations/time.md)를 따릅니다.

## 테스트

테스트와 support는 Kotlin으로 작성합니다. `FunSpec`, Kotest assertion, fixture와 Given/When/Then 기준은 [테스트 가이드](testing.md)를 따릅니다. Spring이 주입하는 테스트 필드에는 `lateinit var`를 허용합니다.

## 포맷

[build.gradle.kts](../../build.gradle.kts)의 Spotless와 ktlint 설정을 사용합니다. 들여쓰기, 개행과 공백은 [.editorconfig](../../.editorconfig)를 따릅니다. 운영 코드, 테스트, support와 Gradle Kotlin DSL에 같은 formatter를 적용합니다. IDE 개인 설정으로 formatter 결과를 덮어쓰지 않습니다.

저장소 루트에서 실행합니다.

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
```
