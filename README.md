# RunMate

러닝 모임을 만들고 참가하는 웹 서비스다. 모임이 만석이면 대기열에 등록되고, 참가 취소로 자리가 나면 첫 대기자가 자동 승급한다. React 프론트엔드와 Java 17·Spring Boot API, PostgreSQL을 사용한다.

## 성능 실험 요약

| 실험 | 비교 조건 | 결과 |
| --- | --- | --- |
| 모임 상세 조회의 행 락 제거 | 같은 모임 상세, 목표 30 RPS, 20초, 개선 전후 각 2회 | 성공 응답 p95 **4.51~4.75초 → 43.50~45.22 ms**. 전송 요청 실패율 **16.03~30.64% → 0%**, 시작 누락 **127~193건 → 0건**. 요청당 SQL은 7회로 동일했다. [측정 결과](docs/LOAD-TEST-READ.md#8단계-개선-후-30-rps-재측정) |
| 최신 모임 첫 페이지 Caffeine 캐시 | 동일 JAR에서 캐시 끔·켬, 검색·필터 없는 최신순 첫 9개, 목표 30 RPS, 20초, 각 2회 | 20초당 목록·전체 개수 SELECT 합계 **1,200회 → 8회(약 99.3% 감소)**. 성공 응답 p95 **71.85~182.19 ms → 14.76~30.14 ms**. 모든 실행에서 실패·시작 누락 0건. [측정 결과](docs/LOAD-TEST-READ.md#최신-모임-첫-페이지-caffeine-캐시) |

- 상세 조회에서 모집 마감 저장을 스케줄러로 옮겼다. 조회는 명시적 행 락 없이 읽기 전용 트랜잭션으로 실행하고, 요청 시각 기준으로 마감 상태를 표시한다. 신청·취소 등 쓰기에는 정원과 대기 순서를 보호하는 모임 행 락을 유지했다.
- 첫 페이지의 공통 응답을 5초간 저장한다. 모임 생성·수정·개최 취소·완료 커밋 뒤 캐시를 비우고, 모집 상태는 읽을 때 시작 시각을 기준으로 다시 판단한다. 참가 인원과 주최자 닉네임은 만료까지 이전 값이 보일 수 있다. SQL 감소율은 목록과 개수 쿼리에 한정하며 HTTP 응답 시간에는 인증이 포함된다.
- 로컬 Windows 한 대에서 API·Docker PostgreSQL·k6가 자원을 공유했다. 조회 데이터는 회원 100명과 모임 1,000개다. 실패율은 전송 요청 기준이고, 시작 누락은 k6가 가상 사용자 상한에 도달해 보내지 못한 요청이다. 캐시 결과는 같은 첫 페이지에 요청이 집중되는 조건이다. [환경과 집계 기준](docs/LOAD-TEST-READ.md#재현-조건)
- 대기 등록과 취소·승급에서 행 락 획득 뒤 SQL을 각각 5→4회, 8→7회로 줄였다. 같은 모임에 신청·취소가 합계 40 RPS로 집중된 최종 혼합 실험에서는 5초 HTTP 시간 초과가 계속 발생했다. HTTP 결과와 DB 커밋은 따로 집계했다. [쓰기 실험](docs/LOAD-TEST-WRITE.md) · [조회·쓰기 혼합 실험](docs/LOAD-TEST-MIXED.md)

마지막 동작 변경 후 일반 테스트 147개와 실제 PostgreSQL 테스트 47개를 통과했다. API에서 생성·수정·개최 취소 뒤 첫 페이지 갱신과 검색·다음 페이지를 확인했고, 캐시가 남아 있는 동안의 모집 마감 경계는 자동 테스트에서 검증했다. [검증 기록](docs/LOAD-TEST-READ.md#최신-모임-첫-페이지-caffeine-캐시)

## 조회 실험 재현

Java 17, Docker Compose, k6가 필요하다. 저장소 루트 PowerShell에서 전용 DB를 시작하고 현재 코드로 API JAR를 빌드한다.

```powershell
docker compose -f load-test/compose.yaml up -d --wait
.\gradlew.bat bootJar
java -jar build/libs/runmate-0.0.1-SNAPSHOT.jar --spring.profiles.active=load-test
```

API가 `127.0.0.1:8081`에서 시작해 스키마를 만든 뒤, 별도 터미널에서 아래 명령을 실행한다. `seed.sql`은 전용 DB `127.0.0.1:5435/runmate_load`의 기존 실험 데이터를 지우고 조회용 데이터를 생성한다.

```powershell
Get-Content -Raw -LiteralPath load-test/seed.sql |
  docker compose -f load-test/compose.yaml exec -T postgres psql -X -U runmate_load -d runmate_load -v read_baseline=1
docker compose -f load-test/compose.yaml exec -T postgres psql -X -U runmate_load -d runmate_load -c 'ANALYZE;'
```

### 상세 조회 전후 비교

개선 전 기준은 `4ea4eaa`다. 별도 체크아웃에서 기준 JAR를 빌드한다. 기준 커밋에는 `load-test` 프로필이 없으므로 아래 옵션으로 동일 포트와 전용 DB를 사용한다. 한 번에 API 하나만 실행하고, 각 버전에서 같은 초기 데이터를 준비한다. 아래 k6 명령과 초기화 스크립트는 `load-test/`가 있는 현재 체크아웃에서 실행한다.

```powershell
# 기준 커밋 체크아웃에서 실행
.\gradlew.bat bootJar
java -jar build/libs/runmate-0.0.1-SNAPSHOT.jar `
  --server.address=127.0.0.1 --server.port=8081 `
  --spring.datasource.url=jdbc:postgresql://127.0.0.1:5435/runmate_load `
  --spring.datasource.username=runmate_load --spring.datasource.password=runmate_load `
  --spring.datasource.hikari.maximum-pool-size=10 `
  --spring.jpa.hibernate.ddl-auto=update --spring.jpa.show-sql=false
```

```powershell
# 기준 및 개선 버전에서 각각 실행
k6 run --quiet -e READ=detail -e RPS=10 -e SECONDS=5 load-test/read-baseline.js
k6 run --quiet -e READ=detail -e RPS=30 -e SECONDS=20 load-test/read-baseline.js
```

20초 측정을 각 버전에서 두 번 실행한다. 락 경합의 대조 실험은 `READ=detail-spread`로 동일 요청량을 모임 100개에 분산한다. [DB 활동 표본과 쿼리 계측](docs/LOAD-TEST-READ.md#재현-조건)

### 첫 페이지 캐시 비교

위에서 만든 개선 JAR를 두 조건에 공통으로 사용한다. 캐시를 끌 때 시작 명령에 `--runmate.events.latest-cache-enabled=false`를 추가하고, 켤 때 `--runmate.events.latest-cache-enabled=true`로 바꿔 재시작한다. 각 조건에서 동일 초기 데이터를 준비하고 10초 예열 후 20초 측정을 두 번 실행한다. 매 측정 직전 통계를 초기화하고, 직후 `pg_stat_statements`의 목록·개수 SELECT 호출을 확인한다.

```powershell
# 각 조건의 예열
k6 run --quiet -e READ=list -e RPS=30 -e SECONDS=10 load-test/read-baseline.js

# 측정할 때마다 아래 세 명령 실행
docker compose -f load-test/compose.yaml exec -T postgres psql -X -U runmate_load -d runmate_load -c 'SELECT pg_stat_statements_reset();'
k6 run --quiet -e READ=list -e RPS=30 -e SECONDS=20 load-test/read-baseline.js
docker compose -f load-test/compose.yaml exec -T postgres psql -X -U runmate_load -d runmate_load -c "SELECT calls, query FROM pg_stat_statements WHERE query ILIKE 'select%from running_events%' ORDER BY calls DESC;"
```

성공 응답의 평균·p95·p99, 완료 RPS, 실패율과 시작 누락은 k6 JSON 요약에서 확인할 수 있다. [캐시 비교 조건과 결과](docs/LOAD-TEST-READ.md#최신-모임-첫-페이지-caffeine-캐시)
