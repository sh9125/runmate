# RunMate 프로젝트 인수인계 — 참가 신청 완료, 정원 초과 방지 시작

## 1. 프로젝트 개요

RunMate는 러닝 이벤트 모집 및 참가 플랫폼이다.

포트폴리오의 핵심 목표는 CRUD 구현을 넘어 다음 경험을 쌓는 것이다.

- 트랜잭션과 동시성 제어
- DB 락과 정원 초과 방지
- 대기자 관리 및 자동 승급
- 조회 성능과 인덱스 개선
- k6 부하 테스트 및 개선 전후 측정
- 추후 규칙 기반 이벤트 추천

현재 회원가입·로그인, 이벤트 생성·목록·상세 조회, 참가 신청까지 구현했다.

## 2. 가장 중요한 작업 방식

나는 직접 코드를 작성하며 배우고 싶다.

- 친구처럼 편하게 반말로 대화한다.
- 한 번에 한 단계를 쉽게 설명한다.
- 내가 직접 작성하고 “했어”, “확인해줘”라고 하면 실제 파일을 읽고 검토한다.
- 오류가 있으면 원인과 수정 방향부터 설명한다.
- 명시적으로 “수정해줘”, “만들어줘”라고 요청하기 전에는 코드를 직접 수정하지 않는다.
- 전체 정답을 한 번에 주기보다 필요한 부분부터 안내한다.
- 테스트 실행과 읽기 전용 확인은 진행해도 된다.
- 기능 완료 후 전체 테스트 → Swagger → 커밋 → 푸시 → PR → 병합 순서로 마무리한다.
- 커밋 메시지는 한국어로 작성한다.
- 노션용 정리는 제목과 번호별 항목으로 작성한다.
- 노션 정리는 일반 개념을 먼저 설명하고 마지막에 RunMate 적용 사례를 넣는다.
- Git과 실행 환경은 문서만 믿지 말고 실제 상태를 확인한다.

## 3. 기술 스택과 실행 환경

- Java 17
- Spring Boot 4.0.7
- Gradle
- Spring Data JPA
- Spring Security / JWT
- PostgreSQL 16
- Docker Compose
- Swagger/OpenAPI

프로젝트 경로:

```text
C:\Users\ku001\IdeaProjects\runmate
```

현재 개발 방식:

- PostgreSQL만 Docker에서 실행
- Spring 서버는 IntelliJ에서 실행
- IntelliJ 실행 인수: `--spring.profiles.active=local`
- Swagger: `http://localhost:8080/swagger-ui/index.html`

전체 테스트:

```powershell
.\gradlew.bat test
```

### DB 연결

| 접속 위치 | 주소 |
|---|---|
| IntelliJ에서 실행한 Spring | localhost:5433 |
| PC의 pgAdmin4 | localhost:5433 |
| Compose 안의 Spring 컨테이너 | postgres:5432 |

DB 이름, 사용자, 비밀번호는 로컬 개발 설정상 모두 `runmate`다.

Compose 서비스:

- `postgres`: 컨테이너 이름 `runmate-postgres`
- `app`: 컨테이너 이름 `runmate-app`
- PostgreSQL 포트 매핑: `5433:5432`
- 데이터 볼륨: `runmate-postgres-data`

프로필별 DB URL:

```text
local:  jdbc:postgresql://localhost:5433/runmate
docker: jdbc:postgresql://postgres:5432/runmate
```

두 프로필 모두 현재 `ddl-auto: update`다.

IntelliJ의 현재 무료 환경에서는 DB 쿼리 실행이 Ultimate 구독 기능으로 제한됐다.
DB 조회·삭제는 PC에 설치된 pgAdmin4의 Query Tool을 사용한다.

Spring을 Docker로 실행할 때는 IntelliJ에서 실행 중인 서버를 먼저 종료해야 한다.
둘 다 호스트의 8080 포트를 사용한다.

## 4. Git 상태

사용자는 참가 신청 PR을 main에 병합했다고 알렸다.

다만 마지막 로컬 확인 결과는 다음과 같다.

- 브랜치: `feature/running-event-participation`
- 작업 트리: 깨끗함
- 로컬 HEAD: `97151da 러닝 이벤트 참가 신청 기능 구현`
- 병합 이후 원격 fetch/pull은 아직 확인하지 않음

따라서 새 작업 전에 main을 동기화해야 한다.

```bash
git switch main
git pull --ff-only origin main
```

다음 브랜치 이름은 아직 정하지 않았다.
추천 이름은 다음과 같다.

```bash
git switch -c feature/running-event-capacity
```

실제 상태를 먼저 확인한 뒤 진행한다.

## 5. 완료 API

```http
POST /api/v1/members/signup
POST /api/v1/auth/login
GET  /api/v1/auth/me

POST /api/v1/running-events
GET  /api/v1/running-events
GET  /api/v1/running-events/{eventId}
POST /api/v1/running-events/{eventId}/participants
```

이벤트 목록은 Pageable 기반 페이징을 사용한다.

## 6. 인증과 기존 코드 방식

현재 Authentication의 principal은 Member 엔티티가 아니라 이메일 문자열이다.

```java
authentication.getName()
```

Controller가 로그인 이메일을 Service에 전달하고, Service가 이메일로 Member를 조회한다.

- 요청 DTO: Lombok class
- 응답 DTO: record
- 엔티티를 API 응답으로 직접 반환하지 않음
- Mapper: static 변환 메서드 + private 생성자
- 간단한 참가 엔티티 생성은 Service에서 빌더 사용
- ID 타입은 Long
- 생성·수정 시각은 JPA Auditing 사용
- 엔티티 기본 생성자는 protected
- 관계는 기본적으로 LAZY 사용

JWT 필터와 Service가 회원을 각각 조회하는 구조다.
인증 principal 개선과 조회 중복 최적화는 아직 하지 않았다.

## 7. 참가 신청 관련 구현 파일

패키지는 `com.runmate.runningevent` 아래에 유지했다.

```text
entity/RunningEventParticipant.java
repository/RunningEventParticipantRepository.java
dto/response/RunningEventParticipantResponseDto.java
mapper/RunningEventParticipantMapper.java
service/RunningEventParticipantService.java
```

기존 파일도 변경했다.

```text
entity/RunningEvent.java
service/RunningEventService.java
controller/RunningEventController.java
```

### 참가 엔티티

```text
RunningEventParticipant
- id: Long, IDENTITY
- runningEvent: RunningEvent, ManyToOne LAZY
- member: Member, ManyToOne LAZY
- createdAt: LocalDateTime, CreatedDate
```

테이블 이름:

```text
running_event_participants
```

FK 컬럼:

```text
running_event_id
member_id
```

두 FK 모두 nullable=false다.

복합 유니크 제약조건:

```java
@UniqueConstraint(
    name = "uk_running_event_participant_event_member",
    columnNames = {"running_event_id", "member_id"}
)
```

같은 회원이 다른 이벤트에 참가하는 것은 허용하고,
동일 회원·동일 이벤트 조합만 중복을 금지한다.

### Repository

```java
public interface RunningEventParticipantRepository
        extends JpaRepository<RunningEventParticipant, Long> {

    boolean existsByRunningEvent_IdAndMember_Id(
            Long runningEventId, Long memberId);
}
```

`existsBy`가 정확한 이름이다.
이전에 `existedBy` 오타를 수정했다.

### 참가 응답 DTO

```java
public record RunningEventParticipantResponseDto(
        Long id,
        Long eventId,
        Long memberId,
        LocalDateTime createdAt
) {
}
```

요청 DTO는 없다.

- 이벤트 ID: URL에서 받음
- 회원: JWT 인증 이메일로 조회
- 참가 시각: 서버 Auditing으로 결정

### Controller

기존 RunningEventController에 추가했다.

```http
POST /api/v1/running-events/{eventId}/participants
```

- @PathVariable로 eventId 수신
- Authentication에서 이메일 확인
- 참가 Service 호출
- 성공 시 201 Created와 응답 DTO 반환
- 요청 본문 없음

## 8. 현재 참가 Service 흐름

메서드:

```java
participateRunningEvent(Long runningEventId, String memberEmail)
```

`@Transactional` 안에서 다음을 처리한다.

```text
이메일로 회원 조회
→ ID로 이벤트 조회
→ existsByRunningEvent_IdAndMember_Id로 중복 확인
→ 참가 엔티티를 빌더로 생성
→ 참가 Repository.save()
→ 이벤트의 increaseCurrentParticipantCount()
→ 저장한 참가 엔티티를 응답 DTO로 변환
```

현재 예외 메시지:

```text
존재하지 않는 회원입니다.
존재하지 않는 러닝 이벤트입니다.
이미 참가한 러닝 이벤트 입니다.
```

모두 IllegalArgumentException을 사용한다.

RunningEvent의 인원 증가 메서드:

```java
public void increaseCurrentParticipantCount() {
    this.currentParticipantCount++;
}
```

조회한 이벤트는 트랜잭션 안의 관리 엔티티이므로 변경 감지로 갱신한다.
참가 인원 변경 후 RunningEventRepository.save()를 다시 호출하지 않는다.

## 9. 이벤트 생성자 자동 참가 설계

RunningEvent 생성 시:

```java
currentParticipantCount = 1;
status = RunningEventStatus.RECRUITING;
```

이 초기값을 유지한다.

RunningEventService.createRunningEvent()에서:

```text
생성자 조회
→ 이벤트 생성·저장
→ savedEvent와 creator로 참가 엔티티 생성
→ 참가 기록 저장
→ 응답 반환
```

이벤트와 생성자의 참가 기록을 같은 트랜잭션에서 저장한다.

중요:

- 생성자는 초기 인원 1명에 이미 포함된다.
- 생성자의 참가 기록을 저장할 때 인원을 추가로 증가시키지 않는다.
- 일반 참가 Service를 그대로 호출하면 인원이 2가 되므로 사용하지 않았다.
- Service 안에서 참가 Repository로 직접 저장했다.
- 참가 엔티티에는 save() 반환값인 savedEvent를 연결한다.

## 10. 테스트 상태

마지막 전체 실행:

```powershell
.\gradlew.bat test
```

결과: BUILD SUCCESSFUL

### RunningEventParticipantServiceTest

4개 테스트 통과:

1. 참가 성공
   - 참가 기록 저장 호출
   - 응답의 eventId와 memberId
   - 초기 인원 1 → 2

2. 중복 참가 실패
   - 중복 조회 true
   - 예외 타입과 메시지
   - save() 호출 안 됨
   - 인원 1 유지

3. 회원 없음
   - 회원 조회 Optional.empty()
   - 예외와 저장 방지

4. 이벤트 없음
   - 회원 조회 성공
   - 이벤트 조회 Optional.empty()
   - 예외와 저장 방지

성공 테스트는 생성자와 참가자를 서로 다른 회원으로 준비했다.

### RunningEventServiceTest

5개 테스트 통과:

- 이벤트 생성 성공
- 회원 없을 때 생성 실패
- 목록 조회 성공
- 상세 조회 성공
- 없는 이벤트 상세 조회 실패

생성 성공 테스트에 참가 Repository @Mock을 추가했다.

ArgumentCaptor로 다음을 확인한다.

- 참가 기록의 member가 creator와 같은 객체
- 참가 기록의 runningEvent가 이벤트 save()에 전달한 객체와 같은 객체
- 응답의 초기 참가 인원은 1

참가 Repository.save() 반환값은 생성 Service에서 사용하지 않아
해당 save()에 별도 thenAnswer 설정을 두지 않았다.

초기 인원 1 검증이 생성 성공 테스트에 중복으로 남아 있을 수 있다.
필수 수정 사항은 아니다.

## 11. 테스트에서 배운 내용

- Mockito 단위 테스트는 실제 DB와 JPA Auditing을 실행하지 않는다.
- 빌더로 만든 엔티티의 ID는 null이다.
- 조회된 엔티티 상태를 재현하려고 ReflectionTestUtils.setField()로 ID를 지정했다.
- ID 설정은 when()보다 먼저 해야 한다.
- `"1L"`은 문자열이므로 안 되고 `1L`을 사용한다.
- when()의 인자는 설정 당시 값으로 평가된다.
- thenReturn은 미리 준비한 값을 반환한다.
- thenAnswer는 호출 인자를 이용해 반환값을 결정할 수 있다.
- 저장 인자를 그대로 반환하도록 thenAnswer를 사용했다.
- ArgumentCaptor는 Mock 메서드에 전달된 인자를 꺼내 검증한다.
- verify(...).save(captor.capture())는 실제 저장을 다시 수행하지 않는다.
- isSameAs는 객체 동일성(==), isEqualTo는 값 비교다.
- 예외 테스트에서는 Service를 assertThatThrownBy 안에서 한 번만 호출한다.
- verify(mock, never())로 저장 방지를 검증한다.
- 기본 Mockito Mock에서 Optional 반환 메서드는 미설정 시 Optional.empty()를 반환한다.
- Optional.empty()는 null과 다르다.
- 없는 이벤트 테스트도 회원 조회는 성공하도록 설정해야 이벤트 조회까지 진행된다.

## 12. Swagger·DB 검증 완료 결과

회원 A: 생성자 ID 2
회원 B: 일반 참가자 ID 3
검증 이벤트 ID: 3

사용자가 캡처로 다음 결과를 제공했고 확인했다.

- 생성자 A의 재신청 → 중복 참가 예외
- 재신청 후 인원 1 유지
- 회원 B 참가 → 201 Created
- 참가 응답에 id, eventId=3, memberId=3, createdAt 존재
- 참가 후 상세 조회 인원 2
- B 재신청 → 중복 참가 예외, 인원 2 유지
- 없는 이벤트 참가 → 이벤트 없음 예외
- JWT 없는 참가 → 401 Unauthorized
- 인증 실패 후에도 인원 2 유지
- DB 참가 테이블에 A와 B 기록 각각 1건
- DB의 current_participant_count=2와 실제 참가 기록 수 일치

이벤트 생성 자체의 201 캡처는 없었지만,
DB에서 생성자의 참가 기록이 저장된 것은 확인했다.

DB 유니크 제약조건을 직접 위반하는 별도 검증은 하지 않았다.
Swagger 중복 요청은 Service 사전 검사에서 차단됐다.

## 13. 기존 테스트 데이터 관련 주의

참가 기능 추가 전에 만든 이벤트는:

- currentParticipantCount는 1
- 생성자의 실제 참가 기록은 없음

그래서 기존 이벤트 생성자가 참가하면 중복 검사에 걸리지 않는 현상을 발견했다.

검증용 로컬 DB에서 회원은 유지하고
이벤트 및 참가 기록을 전부 삭제한 뒤 새 이벤트로 재검증했다.

기존 데이터 자동 보정이나 운영용 마이그레이션은 구현하지 않았다.

삭제 순서:

```sql
DELETE FROM running_event_participants;
DELETE FROM running_events;
```

FK 때문에 참가 기록을 먼저 삭제한다.
일반 DELETE는 ID 시퀀스를 초기화하지 않는다.

## 14. 남아 있는 예외 처리 문제

전역 @RestControllerAdvice / @ExceptionHandler는 아직 없다.

그래서:

- 중복 참가
- 없는 이벤트
- 로그인 시 없는 회원 또는 비밀번호 불일치

등의 IllegalArgumentException이 500으로 응답될 수 있다.

Security 설정은 다음과 같다.

- 인증 실패: 401
- 권한 부족: 403
- 오류 Dispatcher 허용
- 로그인·회원가입·Swagger 접근 허용
- 나머지는 인증 필요

추후 예외를 구분해서 응답하도록 개선할 예정이다.

예시 방향:

- 이벤트 없음 → 404
- 중복 참가 → 409

모든 IllegalArgumentException을 무조건 409로 처리하면 안 된다.
이번 참가 PR에서는 예외 HTTP 매핑을 후속 작업으로 명시했다.

## 15. 다음 작업: 정원 초과 방지와 동시성 제어

아직 구현하지 않은 것:

- 정원 도달 시 참가 차단
- 모집 상태에 따른 참가 제한
- DB 락
- 참가 인원 동시 갱신 보호
- 동시성 테스트
- 대기자 관리와 자동 승급
- k6 부하 테스트

현재는 단순 ++ 방식이므로 동시 요청에서 갱신 유실이 생길 수 있다.
서로 다른 회원의 동시 참가는 복합 유니크 제약조건으로 해결되지 않는다.

다음 진행 방향:

1. main 동기화 및 새 브랜치 생성
2. 정원 초과 방지 요구사항 결정
3. 단순 정원 검사와 순차 단위 테스트 작성
4. 동시 요청에서 어떤 문제가 생기는지 설명·재현
5. DB 락 등 해결 방법 비교 및 선택
6. 실제 DB 기반 동시성 테스트로 검증
7. 이후 대기자 기능으로 확장

단순히 `현재 인원 >= 정원`을 검사하는 것만으로
동시 요청의 정원 초과가 해결됐다고 판단하지 않는다.

먼저 논의할 요구사항:

- 생성자도 정원에 포함: 현재는 포함되어 있음
- 인원이 정원과 같으면 신청 거절
- 정원이 찼을 때 상태를 CLOSED로 바꿀지
- RECRUITING 외 상태의 참가를 제한할지
- 정원 초과 실패에 사용할 예외와 HTTP 응답
- 상태 검사·정원 검사·중복 검사의 순서

아직 결정하지 않은 정책을 구현 완료로 가정하지 않는다.

## 16. PR 작성 스타일

사용자가 원하는 간단한 형식:

```markdown
## 변경 사항

- 구현 내용 나열

## 테스트

- 실행 및 확인 결과 나열

## 후속 작업

- 필요할 때만 추가
```

참가 기능 커밋:

```text
97151da 러닝 이벤트 참가 신청 기능 구현
```

사용자는 PR을 main에 병합했다고 알렸다.
로컬 main 업데이트는 새 대화에서 진행해야 한다.