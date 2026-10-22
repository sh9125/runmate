# 공통 예외 처리 학습과 검증

## 1. 해결하는 문제

기존에는 중복 참가, 없는 이벤트, 비밀번호 불일치를 모두 IllegalArgumentException으로 던졌다. 업무상 예상된 실패도 500으로 나갈 수 있었고 프론트가 실패 이유를 일관되게 구분하기 어려웠다.

모든 IllegalArgumentException을 409로 바꾸면 프로그래밍 오류까지 중복 요청으로 잘못 분류할 수 있다. 예상된 업무 실패만 BusinessException과 ErrorCode로 명시한다.

## 2. 구성 요소

1. ErrorCode: 오류 식별자, HTTP 상태, 사용자 메시지의 목록이다.
2. BusinessException: Service가 어떤 업무 실패인지 ErrorCode를 담아 던진다. RuntimeException을 상속하므로 기존 @Transactional 기본 롤백 규칙이 적용된다.
3. ErrorResponse: HTTP 응답 JSON 형식이다. status/code/message/errors를 가진다.
4. GlobalExceptionHandler: @RestControllerAdvice와 @ExceptionHandler로 Controller 처리 중 전파된 예외를 HTTP 응답으로 변환한다.
5. SecurityErrorResponseWriter: Controller보다 앞에 있는 Security 필터의 401/403도 같은 JSON으로 작성한다.

이 구조에서는 Controller마다 try/catch를 반복하지 않는다. 트랜잭션 예외를 Controller에서 처리한다는 이유로 Service의 롤백이 사라지지 않는다. Service의 트랜잭션 경계 밖으로 예외가 나간 뒤 응답으로 변환하기 때문이다.

## 3. RunMate의 실제 흐름

없는 이벤트 조회:

```text
GET /api/v1/running-events/999
→ RunningEventService.getRunningEvent()
→ Repository 조회 결과 없음
→ throw new BusinessException(ErrorCode.RUNNING_EVENT_NOT_FOUND)
→ GlobalExceptionHandler.handleBusinessException()
→ HTTP 404 + ErrorResponse
```

```json
{
  "status": 404,
  "code": "RUNNING_EVENT_NOT_FOUND",
  "message": "존재하지 않는 러닝 이벤트입니다.",
  "errors": []
}
```

프론트는 문구 대신 code를 기준으로 분기한다. 메시지 표현이 바뀌어도 오류 식별자는 유지할 수 있다.

## 4. 오류 분류

| 상황 | HTTP 상태 | code |
|---|---|---|
| Service에서 회원을 찾지 못함 | 404 | MEMBER_NOT_FOUND |
| 이벤트를 찾지 못함 | 404 | RUNNING_EVENT_NOT_FOUND |
| 이메일/닉네임 중복 사전 검사 | 409 | DUPLICATE_EMAIL / DUPLICATE_NICKNAME |
| 참가 중복 사전 검사 | 409 | ALREADY_PARTICIPATING |
| 로그인 이메일 또는 비밀번호 불일치 | 401 | INVALID_CREDENTIALS |
| 인증 없음/잘못된 토큰/토큰 회원 없음 | 401 | UNAUTHORIZED |
| 접근 권한 없음 | 403 | FORBIDDEN |
| @Valid 요청 DTO 검증 실패 | 400 | VALIDATION_FAILED |
| 잘못된 JSON/타입 불일치/본문 누락 등 | 400 | INVALID_REQUEST |
| 없는 경로 | 404 | RESOURCE_NOT_FOUND |
| 지원하지 않는 HTTP 메서드 | 405 | METHOD_NOT_ALLOWED |
| 지원하지 않는 요청 Content-Type | 415 | UNSUPPORTED_MEDIA_TYPE |
| 예상하지 못한 서버 오류 | 500 | INTERNAL_SERVER_ERROR |

Spring MVC 기본 예외는 ResponseEntityExceptionHandler의 처리 경로를 재사용한다. handleExceptionInternal에서 본문을 통일하고 원래 상태 및 Allow 같은 헤더를 보존한다. 표에 별도로 정의하지 않은 프레임워크 오류는 원래 HTTP 상태를 유지하며 INVALID_REQUEST 또는 INTERNAL_SERVER_ERROR로 분류한다.

로그인 실패 두 종류는 같은 오류로 응답한다. JWT로 인증하려는 회원이 없으면 인증을 만들지 않고 다음 필터로 진행하므로 보호된 경로는 401, 공개된 로그인/회원가입 경로는 기존처럼 접근 가능하다.

## 5. 입력 오류와 내부 오류

errors는 입력 검증에서 문제가 된 field와 message 목록이다. 입력한 비밀번호나 rejectedValue를 포함하지 않는다. 검증 메시지의 언어는 Validator의 로케일 설정에 따라 달라질 수 있다.

알 수 없는 예외는 서버 로그에 기록하고, 클라이언트에는 일반적인 500 메시지만 보낸다. SQL이나 내부 예외 메시지를 응답에 노출하지 않는다.

## 6. 이번 테스트 범위

- 기존 Service 테스트: 예외 타입뿐 아니라 정확한 ErrorCode 및 저장 방지 확인
- AuthService 테스트: 없는 회원/비밀번호 불일치 시 토큰 발급 안 됨, 성공 로그인 유지
- ApiExceptionHandlingTest: 실제 Controller, Advice, Security 체인과 Jackson을 쓰는 MockMvc 웹 테스트
- 웹 테스트의 Service/Repository/JWT 판정은 Mock이다. 실제 DB·실제 JWT 서명·실제 브라우저 검증과 구분한다.
- 웹 테스트에서 메인 설정의 JPA Auditing이 DB를 요구하지 않도록 JpaMetamodelMappingContext만 Mock으로 대체한다.
- 숫자가 아닌 ID, 깨진 JSON, @Valid, 401/403/404/405/409/415/500, 헤더 보존, 입력값/내부정보 비노출 및 정상 JWT 인증 흐름 검증

자동 테스트 명령:

```powershell
.\gradlew.bat test
```

## 7. Swagger로 확인할 항목

검증 상태: 2026-10-22 사용자가 안내한 Swagger 테스트를 모두 확인했다고 보고했다. 직접 캡처를 재검토한 결과는 아니다. 실제 안내한 정상 회귀 검증은 로그인, 참가 및 상세 인원 확인을 포함하며, 이벤트 생성 자체를 이번에 별도로 재검증했다고 단정하지 않는다.

1. JWT 없이 보호된 API → 401 / UNAUTHORIZED
2. 잘못된 로그인 정보 → 401 / INVALID_CREDENTIALS
3. 잘못된 이메일·짧은 비밀번호 → 400 / VALIDATION_FAILED와 errors
4. 로그인 후 없는 이벤트 조회 → 404 / RUNNING_EVENT_NOT_FOUND
5. 이미 참가한 이벤트 재신청 → 409 / ALREADY_PARTICIPATING, 인원 유지
6. 사용 중인 이메일 또는 닉네임 회원가입 → 409와 해당 code
7. 정상 로그인·조회·참가가 기존대로 동작하는지 확인 (사용자 완료 보고)
8. 이벤트 생성 자체의 별도 회귀 검증은 추가로 확인할 수 있음

## 8. 후속 작업과 학습 질문

DB의 유니크 제약 위반을 전부 409로 처리하지 않았다. 동시 요청이 사전 중복 검사를 함께 통과해 DB에서 거절되면 현재는 일반 500으로 처리된다. 동시성 단계에서 제약조건별 분류와 실제 DB 테스트를 추가한다. 정원 제한이나 참가 취소 기능도 이번 변경에 포함되지 않는다.

직접 설명해 볼 질문:
1. Service가 ResponseEntity 대신 BusinessException을 사용하는 이유는?
2. 같은 HTTP 409여도 ErrorCode가 별도로 필요한 이유는?
3. RuntimeException 상속과 트랜잭션 롤백의 관계는?
4. Security 필터 오류에 Advice만으로 충분하지 않은 이유는?
5. 모든 IllegalArgumentException이나 DB 무결성 오류를 409로 바꾸면 왜 안 되는가?
