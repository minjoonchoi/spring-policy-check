# Spring Policy Check — Gradle 커스텀 task

Kotlin Spring 저장소의 규칙을 YAML로 정의하고 **Gradle JVM 안에서 정적 분석하는 커스텀 task**입니다. 빌드 스크립트는 **Kotlin DSL(`.gradle.kts`)**, task·분석기·테스트는 **Kotlin(`.kt`)**으로 구현했습니다. `DefaultTask`에서 Gradle Worker API의 `classLoaderIsolation`으로 Kotlin PSI 파서와 YAML 라이브러리를 프로젝트의 빌드 클래스패스에서 분리합니다.

별도 Rust 바이너리, Cargo, OS/CPU별 실행 파일, 외부 분석 프로세스가 필요하지 않습니다. Kotlin 컴파일러 라이브러리에서는 구문 분석 API만 사용하며 대상 프로젝트를 컴파일하거나 Spring·DB를 실행하지 않습니다. Gradle 자체에는 Spring 정책 분석기가 없으므로 정책 판정 로직은 이 저장소가 제공합니다.

패키지, 모듈 이름과 suffix는 검토용 예시입니다. 현재 분석은 명시된 타입과 소스 인덱스를 사용하는 제한된 정적 분석이며 Kotlin 컴파일러의 전체 타입 추론·심볼 해석을 수행하지 않습니다.

## 실행

JDK 17 이상이 필요합니다. 검증 기준은 Gradle 8.10.2 / JDK 21입니다. 최초 실행에서는 Gradle과 JVM 의존성을 다운로드하고, 포함된 task 구현을 빌드합니다. 이후 변경하지 않은 빌드 로직은 재사용합니다.

```bash
# 정상 샘플 검사
./gradlew policyCheck

# 전체 규칙 테스트와 정상 샘플 검사
./gradlew check

# 경고를 출력하고 성공
./gradlew policyWarningCheck

# 위반 샘플을 검사하고 의도적으로 실패
./gradlew policyViolationCheck

# 테스트만 실행 / 공통 JAR 빌드
./gradlew policySpecTest
./gradlew assemble
```

`policyGoodCheck`는 `policyCheck`의 별칭입니다. v0.1에서 실패 데모였던 루트 `policyCheck`는 정상 데모로 바뀌었고, 실패 데모는 `policyViolationCheck`로 분리했습니다. 실제 프로젝트에 적용한 `policyCheck`는 기본적으로 해당 모듈의 `src/main/kotlin`을 검사합니다.

## Kotlin DSL 구현 구성

| 파일 | 역할 |
| --- | --- |
| `settings.gradle.kts` | 플러그인 포함 빌드 연결 |
| `build.gradle.kts` | 정상·경고·위반 샘플 task와 검증 task 설정 |
| `gradle-plugin/build.gradle.kts` | Kotlin 컴파일, 플러그인 JAR, 테스트·게시 설정 |
| `gradle-plugin/src/main/kotlin/.../PolicyCheckTask.kt` | Gradle 입력·출력과 Worker 실행 |
| `gradle-plugin/src/main/kotlin/.../PolicyEngine.kt` | 이름·인증 경계·JPA 규칙 판정 |
| `policy.yml` | 프로젝트별 규칙 범위·패턴·심각도 설정 |

Kotlin Gradle Plugin 2.2.21로 구현 코드를 빌드합니다. Gradle 8.10.2의 Kotlin DSL에서도 task 타입을 읽을 수 있도록 공개 클래스의 Kotlin language/API 버전은 1.9, JVM target은 17로 맞췄습니다. 소비 프로젝트에는 별도의 Kotlin 플러그인을 적용할 필요가 없습니다. Groovy DSL 소비 프로젝트도 계속 사용할 수 있습니다.

## 기존 Spring 프로젝트에 적용

아직 Gradle Plugin Portal에 게시하지 않았습니다. 우선 소스 체크아웃을 포함 빌드로 연결할 수 있습니다.

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../spring-policy-check/gradle-plugin")
}
```

```kotlin
// 검사할 모듈의 build.gradle.kts
import io.github.minjoonchoi.policy.PolicyCheckTask

plugins {
    id("io.github.minjoonchoi.spring-policy")
}
repositories { mavenCentral() }

tasks.named<PolicyCheckTask>("policyCheck") {
    policyFile.set(rootProject.layout.projectDirectory.file("policy.yml"))
    // modulePath는 project.path, sourceFiles는 src/main/kotlin이 기본값입니다.
}
```

루트에 `policy.yml`을 두고 `scope.modules`를 실제 모듈 경로와 맞춥니다. 예를 들어 `:api:policyCheck`는 기본적으로 `:api` 규칙을 사용합니다. `check` task가 존재하면 `policyCheck`가 자동 연결됩니다. 플러그인은 적용한 프로젝트의 저장소 설정을 사용하므로 사내 Maven 프록시도 사용할 수 있습니다. 기존 `gradle/spring-policy.gradle` 적용 예제는 제거했습니다. 위와 같이 `plugins` 블록과 타입이 지정된 `tasks.named<PolicyCheckTask>`로 설정합니다.

배포 단위는 플랫폼 공통 플러그인 JAR입니다. `./gradlew :gradle-plugin:publishToMavenLocal`로 로컬 Maven 저장소에 플러그인 마커와 JAR을 게시할 수 있습니다. 사내 Maven 게시 저장소는 조직의 설정을 추가해야 합니다. 분석 라이브러리는 Gradle 의존성으로 다운로드·캐시하며 JAR에 합쳐 넣지 않습니다.

## 기업 사용과 라이선스

프로젝트 자체는 MIT입니다. 새 의존성의 정확한 버전과 포함된 제3자 코드·고지를 검토하고 [개발 시 오픈소스 사용 기준](docs/OPEN_SOURCE_POLICY.md) 및 [제3자 고지](THIRD_PARTY_NOTICES.md)에 기록합니다. Kotlin의 최상위 Apache-2.0 표기 외에 컴파일러 JAR에 포함된 코드의 조건도 별도로 기록합니다. 라이선스 검사는 제품 task가 아닙니다.

## 규칙 명세 방식

실제로 읽고 실행하는 설정은 `policy.yml`입니다. `version: 1`에 해당하는 필드를 SnakeYAML의 안전한 로더로 읽고 허용 필드·타입을 엄격히 검증합니다. 중복 YAML 키와 사용자 정의 객체 태그도 거부합니다. 알 수 없는 키, 잘못된 정규식, 중복 rule ID, 지원하지 않는 버전은 실행 오류입니다.

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
| `scope.package-pattern` | Kotlin `package` 선언에 적용하는 Java 정규식. 전체 일치는 `^…$` 사용 |
| `check.type` | 구현된 검사기 종류. 임의의 자연어 규칙을 실행하는 필드가 아님 |
| `message` | 판정 근거를 설명하는 공통 문구 |
| `suggestion` | 해결 방향. 쿼리 검사기는 계산한 후보 코드를 뒤에 붙임 |
| `exclude-path-patterns` | 입력 경로에 대한 정규식. 파싱 전에 제외 |

선언 종류·API·쿼리를 해석하는 로직은 Kotlin 검사기에 있고, 적용 범위·이름·허용 대상·심각도·설명은 YAML로 바꿉니다. 새로운 쿼리 문법이나 분석 종류를 추가하려면 JVM 검사기와 테스트를 함께 추가해야 합니다.

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

## 판정 및 Gradle task 계약

| 상태 | 의미 | Gradle 결과 |
| --- | --- | --- |
| `violation` | 지원하는 명확한 규칙 위반 | severity가 error이면 실패 |
| `suggestion` | 선호하는 구현의 검토 후보 | 설정한 severity가 error가 아니면 성공 |
| `skipped` | 후보를 찾았지만 정보나 지원 문법이 부족함 | info로 표시, 성공 |
| 진단 없음 | 적용한 규칙에서 진단 없음 | 성공 |
| 분석 실행 오류 | 설정·구문·입출력·중복 클래스·빈 입력 오류 | `ANALYSIS_ERROR`로 실패 |

정책 에러는 리포트를 기록한 뒤 `POLICY_VIOLATION`으로 task를 실패시킵니다. Gradle 프로세스 종료 코드는 성공 0, 실패 1이며 이전 Rust CLI의 별도 종료 코드 2는 사용하지 않습니다. task 실행 중 분석 오류가 발생하면 이전 리포트를 지워 오래된 성공 결과가 남지 않게 합니다. Gradle 자체의 입력 검증 단계에서 실패하면 task action은 실행되지 않으므로 기존 리포트가 남을 수 있습니다.

JSON의 schema-version은 1로 유지합니다. `rule`, `status`, `severity`, `code`, `file`, 1부터 시작하는 Unicode 문자 기준 `line`·`column`, `evidence`, `message`, `suggestion`과 적용된 규칙 ID를 기록합니다. 경로는 해당 Gradle 프로젝트 기준입니다. 기본 위치는 `build/reports/policy/policyCheck.json`, 이 저장소의 데모는 `valid.json`, `warnings.json`, `violations.json`입니다.

SKIP은 수정 제안을 포함하지 않습니다. 진단이 없다고 전체 의미 검증을 통과했다는 뜻은 아니며 발견하지 못한 상황까지 SKIP으로 모두 나열하지 않습니다.

## 테스트 자료

- `tests/fixtures/<case>/Example.kt`, 필요한 경우 `Model.kt`: 실제 Kotlin 입력.
- `tests/fixtures/<case>/expected.json`: 기대 rule·code·severity·status·후보 문자열.
- `gradle-plugin/src/test/kotlin/.../AcceptanceTest.kt`: 기존 67개 Kotlin 사례를 변경 없이 실행.
- `QueryShapeTest.kt`: 쿼리 문법과 변환 8개 테스트.
- `RobustnessTest.kt`: 설정·파싱·정렬·Unicode 위치·변수 가림 등 17개 테스트.
- `GradleTaskTest.kt`: TestKit으로 실제 Groovy/Kotlin DSL 소비 프로젝트, 성공·경고·실패, Configuration Cache, UP-TO-DATE, 입력 변경, 빈 입력과 오래된 리포트 제거 확인.
- `TEST-MATRIX.md`: 소스 사례별 기대 결과.

총 102개 테스트(67 + 8 + 17 + TestKit 10개)가 **Kotlin 원문 → PSI → 소스 인덱스·규칙 → 진단** 경로와 Gradle 실행을 검증합니다. 실제 Spring 컨텍스트와 DB를 실행하지는 않습니다.

CI는 JDK를 준비한 뒤 `scripts/verify-gradle.py`로 테스트·플러그인 검증·정상·경고·오류 데모를 확인합니다. 성공하면 공통 JAR과 테스트·진단 리포트를 artifact로 제공합니다. Rust 빌드나 플랫폼별 바이너리 패키징 단계는 없습니다.

## 실행 구조와 성능

`PolicyCheckTask`가 입력과 출력을 선언하고 `AnalyzeWorkAction`에 분석을 위임합니다. classloader isolation은 별도 분석 프로세스를 띄우지 않고 Gradle JVM 안에서 라이브러리 클래스를 분리합니다. task에는 `Project`를 실행 단계에서 캡처하지 않아 Configuration Cache와 함께 사용할 수 있습니다.

정책 파일·Kotlin 소스·모듈 경로·분석 라이브러리 클래스패스를 입력으로, JSON을 출력으로 선언합니다. 변경이 없으면 UP-TO-DATE로 생략합니다. Gradle의 의존성 캐시가 준비되어 있으면 `--offline`으로 실행할 수 있습니다. 리포트 경로 특성상 원격 build cache는 활성화하지 않았습니다.

현재 한 task는 입력 파일 전체를 순서대로 분석합니다. 파일별 영속 캐시나 Kotlin 전체 타입 해석을 제공하지 않으며 다른 Gradle 모듈의 엔티티를 자동 수집하지 않습니다. 입력 파일에는 모두 해당 task의 modulePath가 적용됩니다. PSI 환경은 작업 종료 시 해제합니다. 최초 파서 로딩과 Gradle 설정 비용이 있으므로 이전 Rust 벤치마크를 새 구현의 성능으로 사용하지 않습니다.

## 구현 경계

- Kotlin만 지원하며 Java 소스 분석은 아직 없습니다.
- 기존 NAME/CQ/AUTH/JPA 규칙의 제한된 판정 범위를 유지합니다.
- 일반 함수의 반환 타입 추론, 외부 JAR의 타입 계층, 전체 호출 그래프·데이터 흐름, Spring FilterChain 등록, DB 쿼리 결과 동등성은 검증하지 않습니다.
- 쿼리 제안은 자동 수정이 아닙니다. 알려진 복잡한 매핑은 보수적으로 SKIP합니다.
- 새로운 분석 종류는 Kotlin 검사기와 테스트를 함께 추가해야 합니다. 기존 종류의 범위·패턴·심각도·허용 API는 YAML로 변경합니다.

## 공식 자료

- [Gradle custom tasks](https://docs.gradle.org/8.10.2/userguide/implementing_custom_tasks.html)
- [Gradle Worker API](https://docs.gradle.org/8.10.2/userguide/worker_api.html)
- [Kotlin PSI](https://github.com/JetBrains/kotlin/tree/v2.2.21/compiler/psi)
- [Spring Data JPA query methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
