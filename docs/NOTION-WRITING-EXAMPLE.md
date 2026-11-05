# 노션 정리글 작성 예시

이 문서는 사용자가 제시한 'Lombok 생성자 어노테이션 정리'의 형식과 설명 수준을 재현한 예시다.
새 컨텍스트에서 노션 글을 작성하기 전에 HANDOFF.md의 형식 지침과 함께 참고한다.
아래처럼 짧은 설명과 코드 예시를 연결하고 일반 개념 뒤에 RunMate 적용을 넣는다.
코드 언어 표기는 실제 언어로 지정한다. 원래 예시의 @NotNull 표기는 정확한 Lombok @NonNull로 보정했다.

권장 노션 분류: JAVA. 생성자 주입의 Spring 동작은 Spring 글에서 연결해도 된다.

---

# Lombok 생성자 어노테이션 정리

## 1. 생성자란?

생성자는 객체를 만들 때 호출되는 특별한 코드이다.

```java
Member member = new Member();
```

이때 `new Member()`에서 생성자가 호출된다.
생성자는 객체를 만들 때 필요한 값을 넣어주는 데 사용한다.

```java
Member member = new Member("test@example.com", "password123");
```

---

## 2. @NoArgsConstructor

`@NoArgsConstructor`는 파라미터가 없는 생성자를 만들어준다.

```java
@NoArgsConstructor
public class Member {
    private String email;
    private String password;
}
```

Lombok이 다음 생성자를 만들어주는 것과 같다.

```java
public Member() {
}
```

### JPA에서 필요한 이유

JPA 엔티티에는 public 또는 protected 기본 생성자가 필요하다.

```java
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    // 필드 생략
}
```

JPA에 필요한 생성자를 제공하면서 일반적인 외부 코드에서 빈 객체를 직접 생성하는 것은 제한한다.
protected이므로 같은 패키지나 상속 관계의 접근까지 모두 금지하는 것은 아니다.

---

## 3. @AllArgsConstructor

`@AllArgsConstructor`는 일반적인 인스턴스 필드들을 파라미터로 받는 생성자를 만들어준다.

```java
@AllArgsConstructor
public class Member {
    private String email;
    private String password;
}
```

위 예시에서는 다음 생성자가 만들어진다.

```java
public Member(String email, String password) {
    this.email = email;
    this.password = password;
}
```

### 주의할 점

같은 타입의 필드가 많으면 파라미터 순서를 잘못 전달해도 컴파일될 수 있다.

```java
new Member(email, password, nickname, name, region);
```

생성 의도가 잘 드러나도록 필요한 필드만 받는 생성자나 Builder를 고려한다.
위의 다섯 인자 코드는 필드가 더 많은 클래스의 별도 예시다.

---

## 4. @RequiredArgsConstructor

초기화되지 않은 final 필드와, 초기화되지 않은 Lombok @NonNull 필드를 받는 생성자를 만들어준다.
검증 어노테이션인 @NotNull과 Lombok의 @NonNull은 다르다.

```java
@Service
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
}
```

위 예시에서는 다음 생성자가 만들어진다.

```java
public MemberService(
        MemberRepository memberRepository,
        PasswordEncoder passwordEncoder) {
    this.memberRepository = memberRepository;
    this.passwordEncoder = passwordEncoder;
}
```

Spring은 이 생성자를 통해 필요한 Bean을 주입한다.
Lombok은 생성자를 만들고, Spring은 의존성을 전달하는 역할이다.

---

## 5. 세 어노테이션 비교

| 어노테이션 | 생성자 형태 | 대표적인 사용 예 |
|---|---|---|
| @NoArgsConstructor | 파라미터 없음 | JPA Entity |
| @AllArgsConstructor | 필드들을 파라미터로 받음 | DTO와 테스트 데이터 생성 |
| @RequiredArgsConstructor | 초기화되지 않은 final/@NonNull 필드를 받음 | Service, Controller |

---

## 6. RunMate에서의 사용 예시

### Entity

```java
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    // 나머지 코드 생략
}
```

JPA가 사용할 기본 생성자를 제공하고 외부의 빈 객체 생성을 제한한다.

### Service

```java
@Service
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    // 나머지 코드 생략
}
```

Lombok이 필요한 생성자를 만들고, Spring이 Repository와 PasswordEncoder를 주입한다.
위 코드는 설명을 위해 일부 필드와 어노테이션을 생략한 예시다.

---

## 7. 한 줄 정리

`@NoArgsConstructor`는 파라미터 없는 생성자,
`@AllArgsConstructor`는 필드들을 받는 생성자,
`@RequiredArgsConstructor`는 초기화되지 않은 final/@NonNull 필드를 받는 생성자를 만든다.

---

## 다른 주제에 적용할 때

- 위 어노테이션 내용을 기계적으로 반복하지 말고 제목/번호/짧은 설명/코드/비교/적용/한 줄 정리의 형식을 따른다.
- 사용자가 질문했던 이유와 헷갈린 지점도 해당 개념 설명에 포함한다.
- 길면 주제별 글로 나누고 DB/JPA/JAVA/Spring/테스트/성능 개선/인증과 보안 중 분류를 안내한다.
- 구현한 사실과 설명용 예시, 실제 측정 결과를 구분한다.
