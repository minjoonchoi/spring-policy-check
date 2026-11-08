# Spring Policy Check — Rust 실행형 규칙 명세 v0.1

Rust-based static policy checker for Spring projects, integrated into Gradle tasks.

Kotlin Spring 저장소의 규칙을 YAML로 정의하고, 실제 Kotlin 소스를 Rust 바이너리로 검사하는 검증용 초안입니다. Gradle은 이미 빌드된 바이너리 실행과 입출력 추적을 담당합니다. 검사 경로에 detekt, Kotlin compiler, Spring 애플리케이션 실행은 없습니다.

패키지, 모듈 이름과 suffix는 검토용 예시입니다. 현재 사용자 레포에 적용하거나, 실제 DB·Security FilterChain을 구동한 결과는 아닙니다.

CLI와 바이너리 이름은 `spring-policy`입니다.

## 기업 사용과 라이선스

프로젝트는 MIT이며 의존성은 [오픈소스 사용·고지·배포 기준](docs/OPEN_SOURCE_POLICY.md)에 따라 관리합니다. 회사별 승인 목록을 대신하는 기준은 아니며, 현재 Cargo 의존성 40개는 저장소의 허용 기준을 통과합니다.

[제3자 고지 목록](THIRD_PARTY_NOTICES.md)에 버전·출처·선택한 조건을 기록하고 원문은 `third_party/`에 보존합니다. 새 의존성, 미승인 라이선스, 고지 누락·변경은 CI에서 차단합니다. 배포 tar.gz에는 프로젝트 라이선스와 전체 고지가 포함됩니다. `./gradlew licenseCheck`로 같은 검사를 실행할 수 있으며, 일반 `policyCheck` 속도에는 영향을 주지 않습니다.

## 바로 실행

필요 환경: 소스에서 빌드할 때 Rust/Cargo와 C toolchain. Gradle을 사용할 때 JDK 17 이상. 검증 환경은 Rust 1.99.1, Gradle 8.10.2, Java 21, Linux x86_64입니다. 최초 의존성 다운로드는 네트워크가 필요하고, 배포 바이너리의 검사 실행은 네트워크를 사용하지 않습니다.

```bash
# 도구 개발자의 최초 빌드. 검사할 때마다 수행하지 않습니다.
cargo build --release --locked

# 실제 Kotlin 입력과 예상 진단을 비교하는 테스트
cargo test --locked
# 같은 테스트를 Gradle task에서 실행
./gradlew policySpecTest

# 정상 예제: 성공
./gradlew policyGoodCheck

# 경고 예제: 진단을 출력하고 성공
./gradlew policyWarningCheck

# 의도적으로 규칙을 위반한 예제: 에러를 출력하고 실패
./gradlew policyCheck
```

Rust 빌드를 Gradle로 요청할 경우 `./gradlew policyBuild`를 명시적으로 실행합니다. `policyCheck`는 빌드 task에 의존하지 않습니다. 배포 시 OS/CPU별 미리 빌드한 파일을 제공하는 구조입니다.

실행 파일은 위 명령으로 각 OS에서 빌드합니다. Git에는 소스와 Gradle wrapper를 저장하고, GitHub Actions는 Linux x86_64 릴리스 바이너리를 검증한 뒤 artifact로 제공합니다. Linux 바이너리는 동적 링크되므로 다른 배포판의 libc 호환성은 별도로 확인해야 합니다.

```bash
# 별도 배포한 바이너리를 사용하는 경우
./gradlew policyGoodCheck -PpolicyBinary=/absolute/path/to/spring-policy

# Gradle 없이 직접 실행
target/release/spring-policy check \
  --config policy.yml --module :sample \
  --root samples/warnings --root samples/common \
  --report build/reports/policy/direct.json
```

## 규칙 명세 방식

실제로 읽고 실행하는 설정은 `policy.yml`입니다. `version: 1`에 해당하는 필드를 Rust의 엄격한 설정 타입으로 역직렬화합니다. 알 수 없는 키, 잘못된 정규식, 중복 rule ID, 지원하지 않는 버전은 실행 오류입니다.

```yaml
version: 1
exclude-path-patterns:
  - '(^|/)(build|generated)(/|$)'
rules:
  - id: CQ001
    severity: error
    scope:
      modules: [':sample', ':application']
      package-pattern: '^com\.example\.application\.command\.service$'
    check:
      type: name
      declaration: class
      name-pattern: '^[A-Z][A-Za-z0-9]*CommandService$'
    message: 'CUD 역할 클래스 이름 규칙 위반'
    suggestion: '*CommandService suffix를 사용하세요.'
```

| 필드 | 정확한 의미 |
| --- | --- |
| `id` | 설정 전체에서 고유한 규칙 ID |
| `severity` | `error`, `warning`, `info`. 명확한 위반·제안의 심각도 |
| `scope.modules` | Gradle이 전달하는 `project.path`와 정확히 일치하는 문자열 목록. glob 아님 |
| `scope.package-pattern` | Kotlin `package` 선언에 적용하는 Rust 정규식. 전체 일치는 `^…$` 사용 |
| `check.type` | 구현된 검사기 종류. 임의의 자연어 규칙을 실행하는 필드가 아님 |
| `message` | 판정 근거를 설명하는 공통 문구 |
| `suggestion` | 해결 방향. 쿼리 검사기는 계산한 후보 코드를 뒤에 붙임 |
| `exclude-path-patterns` | 입력 경로에 대한 정규식. 파싱 전에 제외 |

선언 종류·API·쿼리를 해석하는 로직은 Rust에 있고, 적용 범위·이름·허용 대상·심각도·설명은 YAML로 바꿉니다. 새로운 쿼리 문법이나 분석 종류를 추가하려면 Rust 검사기와 테스트를 함께 추가해야 합니다.

## 제공한 규칙

| ID | 요구사항 | 실행 조건 | 기본 결과 |
| --- | --- | --- | --- |
| NAME001 | 특정 모듈·패키지의 일반 클래스 이름 | `api.controller` 최상위 일반 클래스 | `*Controller`가 아니면 ERROR |
| NAME002 | 특정 모듈·패키지의 데이터 클래스 이름 | `api.request` 최상위 data class | `*Request`가 아니면 ERROR |
| CQ001 | CUD 클래스 suffix | `application.command.service` 일반 클래스 | `*CommandService`가 아니면 ERROR |
| CQ002 | R 클래스 suffix | `application.query.service` 일반 클래스 | `*QueryService`가 아니면 ERROR |
| CQ003 | CUD 입력 데이터 suffix | `application.command.model` data class | `*Command`가 아니면 ERROR |
| CQ004 | R 검색 조건 데이터 suffix | `application.query.criteria` data class | `*Criteria`가 아니면 ERROR |
| AUTH001 | 토큰 처리를 Security 경계에 집중 | 등록된 헤더 읽기·인증 API·헤더 애너테이션 | 허용된 패키지와 구성요소 조건을 벗어나면 ERROR |
| JPA001 | `@Query` JPQL보다 파생 메서드 우선 | 지원하는 단순 조회와 타입·바인딩 확인 | WARNING + 메서드 선언 후보 |
| JPA002 | EntityManager 문자열 JPQL보다 `@Query` 우선 | 고정 쿼리와 제한된 실행 chain | WARNING + 애너테이션 후보 |
| JPA003 | Native SQL보다 JPA 활용 검토 | 단순 SQL과 명시적인 엔티티 매핑 확인 | INFO + JPQL 검토 후보 |

### 이름 및 CUD/R 역할

이 버전의 역할 판정은 **모듈과 패키지의 명시적 분류**입니다. `save()` 호출이나 `@Transactional(readOnly=true)`만으로 클래스 전체의 역할을 추정하지 않습니다. 변경 과정에서 조회도 수행하는 Command 클래스는 정상입니다.

일반 클래스 규칙은 data class에 적용되지 않으며, data class 규칙은 일반 클래스에 적용되지 않습니다. interface, enum, annotation class, object, 지역·중첩 클래스는 이름 규칙 대상에서 제외합니다. 이름을 맞췄다고 실제 읽기 전용 동작까지 검증된 것은 아닙니다. 쓰기 효과·dirty checking·간접 호출 분석은 아직 구현하지 않았습니다.

### AUTH001 — 인증 처리 경계

다음 코드 사실을 검사합니다.

1. 명시적으로 `jakarta.servlet.http.HttpServletRequest` 또는 `javax.servlet.http.HttpServletRequest`로 선언된 수신 객체의 `getHeader`·`getHeaders` 호출.
2. `Authorization`, `X-Auth-Token` 등 설정한 인증 헤더. 대소문자를 구분하지 않습니다.
3. Spring의 `@RequestHeader`에 인증 헤더를 직접 바인딩하는 선언.
4. `restricted-calls`에 등록한 `타입 FQN#메서드` 호출. 예: `JwtDecoder#decode`, `BearerTokenResolver#resolve`, 팀의 `TokenReader#extract`.

허용 조건은 **허용 패키지 AND (허용 타입 OR 허용 상위 타입)**입니다. `security` 패키지에 넣기만 하거나 클래스 이름을 `Filter`로 끝내는 것으로 통과하지 않습니다. source index에 있는 상속 관계는 추적하며 순환·과도한 깊이는 16단계에서 중단합니다. 허용 상위 타입은 Filter·Resolver·Provider·Decoder를 예시로 지정했습니다.

Controller가 공통 `TokenReader.extract`를 직접 호출하는 것도 위반입니다. `@AuthenticationPrincipal`을 통해 인증 결과를 받는 것은 정상입니다. 전송 헤더를 설정하는 `setBearerAuth()`는 이 수신 토큰 규칙의 대상이 아닙니다.

문자열 리터럴, 현재 파일 최상위 `const val`, 명시적으로 import한 `HttpHeaders.AUTHORIZATION`을 해석합니다. 명시적 import 별칭과 지역 변수 shadowing을 구분합니다. 타입이 추론되는 수신 객체, 동적 헤더 이름, 인식 가능한 복잡한 수신 표현식은 SKIP합니다.

**구현 경계:** 전체 인증 data flow, 모든 Map/HttpHeaders 접근, reflection, typealias, 함수 간 전파, 외부 jar의 상속·오버로드 해석은 구현하지 않았습니다. 등록되지 않은 새 토큰 라이브러리는 API 목록에 추가해야 합니다. 실제 SecurityFilterChain 등록·순서·URL 보호 범위는 이 정적 규칙만으로 검증되지 않습니다.

### JPA001 — 파생 메서드 제안

v0.1의 지원 패턴은 다음 전체 구조입니다.

```sql
select u from User u
where u.email = :email and u.age >= :age
order by u.id desc
```

- 단일 엔티티 전체 선택. 명시적으로 Spring Data Repository를 상속한 선언.
- `=`, `>`, `>=`, `<`, `<=`와 AND 연결. 기본 조건 수는 3개 이하.
- named parameter 또는 1부터 시작하는 positional parameter.
- 선택적으로 단일 속성의 `order by … asc|desc`.
- 엔티티 속성과 메서드 인자의 이름·기본 타입, 인자 재사용 및 추가 인자 유무 확인.
- nullable 인자는 모두 보수적으로 SKIP. 지원 속성은 String/숫자/Boolean 기본형.
- 반환 타입은 non-null `List<Entity>`. source index의 Repository 엔티티와 일치해야 함.
- `@Param` 및 import alias, 현재 파일의 상수 쿼리 지원.

예상 후보:

```kotlin
fun findByEmailAndAgeGreaterThanEqualOrderByIdDesc(email: String, age: Int): List<User>
```

OR, JOIN, DISTINCT, projection, COUNT, IN, BETWEEN, 관계 속성 경로, 임의 함수, Pageable/Sort, Enum/시간/변환 타입, named query, 동적 쿼리, Lock/QueryHints/EntityGraph, 복잡한 Repository 상속 등은 제한 범위 밖입니다. UPDATE/DELETE는 `BULK_DML`로 SKIP합니다. 문자열 일부만 일치시켜 후보를 만들지 않고 전체 토큰이 지원 문법에 해당해야 합니다.

기존 JPQL과 파생 조회의 null 처리·문자열 검색 동작이 다를 수 있고, 벌크 삭제와 파생 삭제는 엔티티 생명주기·실행 방식이 다릅니다. 따라서 제안의 지원 범위를 제한했고 자동 수정은 제공하지 않습니다.

### JPA002 — EntityManager JPQL을 애너테이션으로

```kotlin
em.createQuery("select u from User u where u.email = :email", User::class.java)
    .setParameter("email", email)
    .resultList
```

명시적 EntityManager 타입, 상수 JPQL, 지원 SELECT 구조, 엔티티 class literal, named parameter 바인딩, `resultList`로 끝나는 chain을 확인합니다. 위 코드는 `@Query("select u from User u where u.email = :email")` 후보를 출력합니다. 바인딩·반환 타입·트랜잭션 경계를 보존하라는 안내를 붙입니다.

`createQuery(CriteriaQuery)`는 이 규칙 대상이 아닙니다. `createNamedQuery`도 대상이 아닙니다. 동적 쿼리, Query 객체를 여러 문장에서 조립하는 코드, 설정/힌트/페이지 제한/lock, positional binding, 특수 결과 변환은 SKIP합니다.

### JPA003 — Native SQL의 JPA 활용 제안

검출 경로는 `@Query(nativeQuery = true)`, `@NativeQuery`, `EntityManager.createNativeQuery`입니다. 지원 SELECT 구조는 다음과 같습니다.

```sql
select u.* from users u where u.user_email = :email
```

`@Entity`, 명시적인 `@Table(name="users")`, 사용한 컬럼의 `@Column(name="user_email")`을 source index에서 확인하여 다음 후보를 만듭니다.

```kotlin
@Query("select u from User u where u.email = :email")
```

DB 전용 문법, 복잡한 JOIN·집계·함수, converter, 관계·상속·복잡한 매핑, 확인할 수 없는 테이블·컬럼은 SKIP합니다. 단순 매핑이 확인되어도 **런타임 필터·자동 converter·DB 실행계획·결과 동일성은 검증하지 않았으므로 INFO 수준의 검토 후보**입니다.

## 판정·CLI 계약

| 상태 | 의미 | 기본 exit code |
| --- | --- | --- |
| `violation` | 지원하는 명확한 규칙 위반 | error가 하나라도 있으면 1 |
| `suggestion` | 더 선호하는 구현의 검토 후보 | warning/info만 있으면 0 |
| `skipped` | 해당 후보를 찾았지만 필요한 정보나 지원 문법이 부족함 | info로 표시, 0 |
| 진단 없음 | 적용한 규칙에서 진단이 없음. 완전한 의미 검증 성공을 뜻하지 않음 | 0 |
| 분석 실행 오류 | 설정·구문·입출력·중복 클래스·빈 입력 오류 | 2 |

JSON 리포트에는 `rule`, `status`, `severity`, `code`, `file`, 1부터 시작하는 Unicode 문자 기준 `line`·`column`, `evidence`, `message`, `suggestion`이 들어갑니다. SKIP에는 수정 제안을 붙이지 않습니다. 적용된 규칙 ID 목록도 기록하여 잘못된 모듈·패키지 범위 설정을 확인할 수 있습니다. 발견하지 못한 임의의 의미적 상황까지 모든 SKIP으로 열거하는 도구는 아닙니다.

## 테스트 자료

- `tests/fixtures/<case>/Example.kt`: 실제 Kotlin 입력.
- `tests/fixtures/<case>/Model.kt`: 필요한 경우 엔티티·공통 타입 입력.
- `tests/fixtures/<case>/expected.json`: 기대 rule·code·severity·status·후보 문자열.
- `tests/acceptance.rs`: 67개 Kotlin source acceptance test.
- `src/query.rs`: 쿼리 grammar·변환과 제외 조건 8개 test.
- `tests/robustness.rs`: 설정 오류·실패 코드·병렬 결과 안정성 등 15개 test.
- `TEST-MATRIX.md`: 전체 source acceptance case 목록.
- `verification/`: 실제 실행 결과와 성능 측정 데이터.

테스트는 정규화된 가짜 분석 결과를 입력하지 않고 **Kotlin 원문 → 구문 트리 → source index·규칙 → 진단** 경로를 실행합니다. JVM 컴파일러·실제 Spring 컨텍스트·DB를 실행하는 integration test는 포함하지 않았습니다.

### GitHub Actions

`.github/workflows/ci.yml`은 push와 PR마다 Rust 포맷·릴리스 빌드를 확인하고, `scripts/verify-gradle.py`로 90개 테스트와 정상·경고·오류 Gradle task를 실행합니다. 의도적인 오류 예제는 실패 종료와 예상 진단을 모두 확인해야 CI가 통과합니다.

성공한 실행의 `spring-policy-linux-x86_64` artifact에는 실행 권한을 보존한 바이너리 tar.gz, SHA-256 체크섬과 JSON 리포트가 포함됩니다. `verification/`은 초기 프로토타입 측정 기록이며, 최신 커밋의 검증 결과는 해당 Actions 실행을 기준으로 확인합니다.

## Gradle 연결·속도

`gradle/spring-policy.gradle`은 기존 Spring 모듈에 적용할 최소 연결 예시입니다. 바이너리와 policy 파일을 루트에 배치하고 `scope.modules`를 실제 `project.path`에 맞추세요. 여러 소스 루트가 필요한 경우 `--root`를 추가합니다. 다른 모듈의 엔티티 정보도 필요하면 해당 소스를 함께 제공하는 방식으로 확장해야 합니다. 현재 예시 script는 그 모듈의 `src/main/kotlin`만 전달합니다.

검사 task는 바이너리·YAML·Kotlin 입력·모듈 경로를 inputs로, JSON을 output으로 선언합니다. 변경이 없으면 Gradle이 `UP-TO-DATE`로 생략합니다. Configuration Cache 재사용도 검증했습니다. 파일이 바뀌어 다시 실행되면 현재 구현은 주어진 소스 전체를 다시 분석합니다. 내부 파일별 영속 캐시·remote build cache·변경 영향도 분석은 아직 구현하지 않았습니다.

Rust에서 파일별 파싱·분석을 병렬 실행하고 결과는 경로·위치·rule ID로 정렬합니다. 바이너리 자체는 JVM을 시작하지 않지만, `./gradlew`의 시작·설정 비용은 별도로 존재합니다. `scripts/benchmark.py`는 그 차이를 분리해서 측정합니다. 수치는 `verification/benchmark.json`을 확인하세요.

## 구현 기반과 추가할 범위

구문 파싱: Tree-sitter Kotlin NG 1.1.0. import와 명시적 타입, 소스 안의 클래스·속성·상속을 제한적으로 해석하는 Rust index. Kotlin 컴파일러와 동등한 타입 분석은 아닙니다. parser가 Kotlin 입력을 해석하지 못하면 오류 exit 2로 끝납니다. 해당 grammar가 최종 한 줄 멤버의 닫는 중괄호 앞에 만드는 가상 `_class_member_semi` 누락만 좁게 허용하며, 그 밖의 error recovery는 통과시키지 않습니다.

다음 확장은 실제 레포의 오탐·미탐 사례를 fixture로 추가하면서 진행합니다: Kotlin 타입 추론/오버로드·data flow, module classpath metadata, FilterChain 등록 검증, 읽기·쓰기 효과 분석, 더 넓은 JPQL/SQL grammar, baseline·사유 있는 예외, SARIF, OS별 배포 자동화. 이 파일의 구현 범위와 구별해 관리합니다.

## 참고한 공식 자료

- [Tree-sitter Rust bindings](https://github.com/tree-sitter/tree-sitter/tree/master/lib/binding_rust)
- [Tree-sitter Kotlin grammar](https://github.com/tree-sitter-grammars/tree-sitter-kotlin)
- [Gradle incremental build](https://docs.gradle.org/current/userguide/incremental_build.html)
- [Spring Data JPA query methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [Spring Security bearer token architecture](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
