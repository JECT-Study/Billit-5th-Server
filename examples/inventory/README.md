# 재고 예약 예제

UseCase, Repository와 Adapter, Kotlin JDSL 잠금 조회, HTTP 계약과 통합 테스트를 참고하기 위한 코드입니다. 실제 서비스 기능이 아니며 Gradle 소스 세트에 포함하지 않습니다. 앱의 컴파일, Entity 스캔, API 제공과 CI 테스트 대상에서 제외됩니다.

| 경로 | 내용 |
| --- | --- |
| `src/main/kotlin` | 재고 예약과 최근 예약 조회 구현 |
| `src/test/kotlin` | HTTP, 조회, 동시성, 트랜잭션 검증 예제와 통합 테스트 지원 |
| [api-docs/inventory.yml](api-docs/inventory.yml) | 예제 API 계약. 공통 정의는 실제 앱의 명세를 참조합니다. |

패키지 이름과 구현은 참고할 수 있도록 유지합니다. 테스트의 설정과 의존성은 루트 프로젝트를 전제로 하며, 이 폴더만으로 실행할 수 없습니다. `learning_stocks`, `learning_reservations`의 Flyway migration은 포함되어 있지 않습니다. 실행 대상으로 다시 편입할 때는 테이블 migration, 테스트 설정과 루트 OpenAPI 참조를 함께 구성해야 합니다.
