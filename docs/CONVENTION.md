# Hipster 프로젝트 컨벤션 가이드

본 문서는 Hipster 프로젝트의 원활한 협업을 위한 커밋 메시지, 코딩, 패키지 구조에 대한 규칙을 정의합니다. 모든 팀원은 본 문서를 숙지하고 준수해야 합니다.

---

## 1. 커밋 컨벤션 (Commit Convention)

일관성 있는 커밋 히스토리 관리를 위해 Angular Commit Message Convention을 따릅니다.

### 1.1. 커밋 메시지 형식

```
type(scope): subject
```

- **type**: 커밋의 종류 (feat, fix, docs, style, refactor, test, chore)
- **scope**: 변경된 코드의 범위 (선택 사항)
- **subject**: 커밋에 대한 간결한 요약

**[규칙]**
- 제목은 50자 이내, **한글**로 작성하며 현재형/명령문으로 서술합니다.
- 본문에는 '무엇을, 왜' 변경했는지 상세히 기술합니다.

### 1.2. Type 종류

| 타입      | 설명                               |
| --------- | ---------------------------------- |
| **feat**  | 새로운 기능 추가                   |
| **fix**   | 버그 수정                          |
| **docs**  | 문서 추가 또는 수정                |
| **style** | 코드 포맷팅, 세미콜론 누락 등 (비즈니스 로직 변경 없음) |
| **refactor** | 코드 리팩토링 (결과 변경 없이 내부 구조 개선) |
| **test**  | 테스트 코드 추가 또는 수정         |
| **chore** | 빌드, 패키지 매니저 등 기타 잡일     |

---

## 2. 코딩 컨벤션 (Coding Convention)

기본적으로 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)를 따르며, 아래 규칙들을 추가로 적용합니다.

### 2.1. JavaDoc
- 모든 `public` 메서드에 JavaDoc을 다는 것을 지양합니다.
- 복잡한 비즈니스 로직이나, 파라미터/반환값에 대한 설명이 반드시 필요한 **핵심 API**에 한해서만 작성을 권장합니다.

### 2.2. 불변성(Immutability) 우선
- **DTO**: 불변 데이터 객체(DTO)에는 `record` 타입을 적극적으로 사용하여 Boilerplate 코드를 줄이고 데이터 불변성을 보장합니다.
- **변수**: 재할당이 필요 없는 모든 변수(지역 변수, 필드)에는 `final` 키워드를 사용합니다.
- **컬렉션**: 정적 컬렉션 생성 시 `List.of()`, `Set.of()` 등을 사용해 불변 컬렉션을 만듭니다.

### 2.3. 함수형 프로그래밍 스타일
- **Stream API**: 단순 `for-loop` 보다는 데이터 처리의 의도가 명확히 드러나는 Stream API 사용을 지향합니다.
- **Optional**: `null`을 반환할 가능성이 있는 메서드는 `Optional<T>`을 반환하여, 호출하는 쪽에서 NPE 처리를 강제하도록 합니다.

### 2.4. Entity 설계
- **@Setter 금지**: Entity의 일관성 유지를 위해 `@Setter` 사용을 금지하고, 생성자나 비즈니스 메서드를 통해 상태를 변경합니다.
- **지연 로딩**: 모든 연관관계는 N+1 문제 방지를 위해 `FetchType.LAZY`를 기본으로 사용합니다.

### 2.5. 설정 정보 관리
- `@ConfigurationProperties`를 사용하여 관련 설정들을 하나의 `record` 또는 클래스로 묶어 타입-안전하게 관리합니다. `@Value`의 사용은 지양합니다.

---

## 3. 패키지 및 폴더 구조 (Package & Directory Structure)

프로젝트는 도메인 중심의 계층형 아키텍처(Layered Architecture)를 따릅니다.

### 3.1. 최상위 패키지 규칙
- **기본 경로**: `com.hipster.{domain_name}`
- **원칙**: 기능(Feature) 및 도메인별로 패키지를 최상위에서 분리합니다.
- **예시**:
  - `com.hipster.album`: 앨범 관련 모든 로직
  - `com.hipster.user`: 회원 관련 모든 로직
  - `com.hipster.global`: 공통 설정, 예외 처리, 보안 등 전역적으로 사용되는 코드

### 3.2. 도메인 내부 계층 구조
각 도메인 패키지(`com.hipster.album` 등) 하위에는 아래 5개 패키지를 필수로 구성합니다.

1.  **`controller`**
    - **역할**: 웹 요청(HTTP)의 End-point. 요청을 받아 서비스 계층에 위임하고, 처리된 결과를 DTO로 변환하여 응답합니다.
    - **규칙**: 비즈니스 로직을 포함하지 않습니다.

2.  **`service`**
    - **역할**: 핵심 비즈니스 로직을 수행하고 트랜잭션을 관리합니다. (`@Transactional`)
    - **규칙**: Controller와 Repository를 연결하며, DTO와 Entity 간의 변환을 책임집니다.

3.  **`repository`**
    - **역할**: 데이터베이스 접근 계층(Data Access Layer). Spring Data JPA 인터페이스가 위치합니다.
    - **규칙**: DB CRUD 작업 및 QueryDSL을 이용한 동적 쿼리를 담당합니다.

4.  **`dto`**
    - **역할**: 계층 간 데이터 전송 객체(Data Transfer Object).
    - **규칙**: Controller와 Service 사이, Service 내부 등에서 데이터 교환에 사용됩니다. `record` 타입 사용을 적극 권장합니다.

5.  **`domain`**
    - **역할**: 데이터베이스 테이블과 1:1로 매핑되는 핵심 도메인 모델(Entity)이 위치합니다.
    - **규칙**: `Enum`, 값 객체(Value Object) 등 도메인과 관련된 객체들을 포함합니다.

### 3.3. 예시 디렉토리 구조
```text
src/main/java/com/hipster
├── global/              # 공통 설정 (Config, Exception, Security 등)
├── user/                # [User Domain]
│   ├── controller/      # UserController
│   ├── service/         # UserService
│   ├── repository/      # UserRepository
│   ├── dto/             # UserRequest, UserResponse
│   └── domain/          # User (Entity)
└── album/               # [Album Domain]
    ├── controller/      # AlbumController
    ├── service/         # AlbumService
    ├── repository/      # AlbumRepository
    ├── dto/             # AlbumRequest, AlbumResponse
    └── domain/          # Album (Entity), Release (Entity)
```