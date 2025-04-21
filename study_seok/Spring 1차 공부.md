# Spring 1차 공부

----------------------



###  1. 프로젝트 환경 설정

#### ✅ 필수 환경

- Java **17 이상** 설치 (21도 가능)
- IDE: IntelliJ IDEA 또는 Eclipse
- Gradle 프로젝트로 생성 (Groovy 기반)
- Spring Boot **3.x.x 이상** 선택
- 필수 의존성: `Spring Web`, `Thymeleaf`

#### ✅ 프로젝트 생성

- `https://start.spring.io` 에서 설정
- Group: `hello`, Artifact: `hello-spring`
- Packaging: Jar, Language: Java
- Java: 17 or 21

#### ✅ 주요 설정

- `javax` → `jakarta`로 전환 (스프링 부트 3.0 이상에서 필수)
  - ex: `javax.persistence.Entity` → `jakarta.persistence.Entity`
- H2 데이터베이스는 2.1.214 이상 버전 필요

#### ✅ 라이브러리 구성

- `spring-boot-starter-web`: MVC 웹 개발
- `spring-boot-starter-thymeleaf`: 템플릿 엔진
- `spring-boot-starter-logging`: 기본 로깅(logback)
- 테스트 라이브러리: JUnit, Mockito, AssertJ 등 포함

#### ✅ View 확인

- 정적 HTML 파일: `resources/static/index.html`
- 템플릿 파일: `resources/templates/hello.html`

#### ✅ 빌드 및 실행

```
bash복사편집./gradlew build
cd build/libs
java -jar hello-spring-0.0.1-SNAPSHOT.jar
```

------

###  2. 스프링 웹 개발 기초

#### ✅ 정적 컨텐츠

- `resources/static` 내에 HTML 파일 두면 바로 서빙 가능
- ex: `hello-static.html` → `http://localhost:8080/hello-static.html`

#### ✅ MVC와 템플릿 엔진

- Controller → Model → View 흐름

```
java복사편집@GetMapping("hello-mvc")
public String helloMvc(@RequestParam("name") String name, Model model) {
    model.addAttribute("name", name);
    return "hello-template";
}
```

- View: `hello-template.html` (Thymeleaf 문법 사용)

#### ✅ API 반환

- `@ResponseBody` 사용
  - 문자열 반환: "hello spring"
  - 객체 반환: JSON 자동 변환 (`HttpMessageConverter` 작동)

```
java복사편집@GetMapping("hello-api")
@ResponseBody
public Hello helloApi(@RequestParam("name") String name) {
    Hello hello = new Hello();
    hello.setName(name);
    return hello;
}
```

------

###  3. 회원 관리 예제 – 백엔드 개발

#### ✅ 요구사항

- 기능: 회원 등록, 회원 목록 조회
- 데이터: 회원 ID, 이름

#### ✅ 구조 설계

- Controller: 웹 계층
- Service: 비즈니스 로직
- Repository: 데이터 저장 (Memory 기반)
- Domain: `Member` 엔티티 클래스

#### ✅ 리포지토리 개발

```
java복사편집public interface MemberRepository {
    Member save(Member member);
    Optional<Member> findById(Long id);
    Optional<Member> findByName(String name);
    List<Member> findAll();
}
```

- 메모리 구현체 `MemoryMemberRepository`에서는 `HashMap` 사용

#### ✅ 서비스 개발

- 중복 회원 검증 (`validateDuplicateMember`)
- `join()`, `findMembers()`, `findOne()` 메서드

#### ✅ 단위 테스트

- JUnit5 기반 테스트 (`@Test`)
- 독립적인 테스트 환경 위해 `@BeforeEach`, `@AfterEach` 사용

