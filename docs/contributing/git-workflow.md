# Git 작업 규칙

## 작업 흐름

1. 작업 전에 이슈를 생성합니다. 기능과 버그는 해당 템플릿을 사용하고, 담당자와 작업 유형 라벨을 지정합니다.
2. `develop`에서 작업 브랜치를 생성합니다. 브랜치 이름에 이슈 번호를 포함합니다.
3. 의미 있는 변경 단위로 나누어 커밋합니다.
4. `develop`을 대상으로 PR을 생성하고 본문에 `Closes #12`처럼 관련 이슈를 연결합니다.
5. 작성자 외 팀원 1명의 Approve와 필수 CI 통과 후 머지합니다.
6. 일반 작업 PR은 Squash merge하고 작업 브랜치를 삭제합니다.

## 브랜치

| 브랜치 | 용도 |
| --- | --- |
| `main` | 배포 버전 |
| `develop` | 개발 통합, GitHub 기본 브랜치 |
| `feat/{이슈번호}-{요약}` | 기능 개발 |
| `fix/{이슈번호}-{요약}` | 버그 수정 |
| `refactor/{이슈번호}-{요약}` | 동작 변화 없는 코드 개선 |
| `chore/{이슈번호}-{요약}` | 빌드, 패키지, 설정 |
| `docs/{이슈번호}-{요약}` | 문서 |
| `test/{이슈번호}-{요약}` | 테스트 |
| `style/{이슈번호}-{요약}` | 코드 포맷 |
| `hotfix/{이슈번호}-{요약}` | 배포 중 긴급 수정 |

- 요약은 영문 소문자와 하이픈으로 작성합니다. 예: `feat/23-expense-split-form`
- `main`, `develop`에 직접 push하지 않습니다.
- `hotfix`는 `main`에서 분기하고 `main` 대상 PR로 반영합니다. 이후 `develop`에도 같은 수정이 반영되도록 별도 PR을 생성합니다.

## 커밋과 PR 제목

형식은 `type: 제목`으로 통일합니다.

```text
feat: 정산 미리보기 화면 추가

- 정산을 확정하기 전에 참여자별 금액을 확인하도록 미리보기 추가
```

| type | 의미 |
| --- | --- |
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 코드 개선 |
| `style` | 포맷팅 등 코드 스타일 수정. UI 변경은 `feat` 또는 `fix` 사용 |
| `chore` | 빌드, 패키지, 설정 |
| `docs` | 문서 |
| `test` | 테스트 |

- 제목 줄은 `type: `을 포함해 50자 이내로 작성합니다. 제목은 한국어로 쓰고 끝에 마침표를 붙이지 않습니다.
- 본문은 선택 사항이며 무엇을 변경했고 왜 변경했는지 작성합니다.
- PR 제목도 같은 규칙을 따릅니다. Squash 커밋 제목은 `feat: 제목 (#PR번호)` 형식으로 PR 제목에 PR 번호를 덧붙입니다. 자동으로 붙는 PR 번호는 50자 제한에서 제외합니다.

## 머지와 릴리스

- 일반 작업 PR과 hotfix PR은 Squash merge합니다.
- 릴리스는 `develop`에서 `main`으로 PR을 생성하고 Merge commit으로 머지한 뒤 `v0.1.0` 형식의 태그를 부여합니다.
- 릴리스 PR에도 작성자 외 1명의 Approve와 필수 CI 통과를 적용합니다.
- 머지 후 작업 브랜치는 삭제하고 `main`, `develop`은 유지합니다.

## 라벨과 템플릿

- 기본 라벨은 `feature`, `bug`, `refactor`, `chore`, `docs`로 구성하고 필요한 작업 유형은 추가합니다.
- 담당자는 Assignee로 지정하고 작업 유형은 라벨로 구분합니다.
- [기능 이슈 템플릿](../../.github/ISSUE_TEMPLATE/feature.md), [버그 이슈 템플릿](../../.github/ISSUE_TEMPLATE/bug.md), [PR 템플릿](../../.github/pull_request_template.md)을 사용합니다.
- PR에서 프로젝트가 요구하는 검사, 테스트와 빌드 결과를 확인합니다. 백엔드의 실행 명령은 [실행과 검증](../../AGENTS.md#실행과-검증)과 [CI](../../.github/workflows/ci.yml)를 따릅니다.

## GitHub 저장소 설정

- 기본 브랜치를 `develop`으로 지정합니다. `Closes #번호`를 통한 이슈 자동 종료는 기본 브랜치 대상 PR에서 동작하므로 이슈 자동 종료 설정도 활성화합니다.
- `main`, `develop`에 브랜치 보호를 적용합니다. PR, Approve 1명과 필수 CI 통과를 요구하고 직접 push와 보호 규칙 우회를 허용하지 않습니다.
- Squash merge와 Merge commit을 허용하고 Rebase merge는 비활성화합니다. Merge commit은 릴리스 PR에서만 사용합니다.
- `main`에는 릴리스 Merge commit을 허용하도록 선형 이력 필수 설정을 적용하지 않습니다.
- Squash 기본 커밋 메시지는 PR 제목을 사용하도록 설정합니다.
- `Automatically delete head branches`를 활성화하고 `main`, `develop`은 보호 규칙으로 삭제를 방지합니다.

이 절은 적용해야 할 설정을 정의하며, 실제 GitHub 설정의 적용 여부는 별도로 확인합니다.
