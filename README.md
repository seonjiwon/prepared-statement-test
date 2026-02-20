# 🧪 Prepared Statement Test

## 1. 기존 테스트 요약
참고: [🔗 우리의 애플리케이션에서 PreparedStatement는 어떻게 동작하고 있는가](https://tech.kakaopay.com/post/how-preparedstatement-works-in-our-apps/)

### 테스트 환경
- 단일 Connection 사용
- 반복 횟수: 20,000회
- 단순 조건 쿼리 실행
- 반복문 내부에서 prepareStatement() 호출
- ResultSet, PreparedStatement 매 반복마다 close()

### 결과

| 번호 | useServerPrepStmts | cachePrepStmts | 실행시간 |
| --- | --- | --- | --- |
| 1 | true | true | 약 4분 |
| 2 | true | false | 약 21분 |
| 3 | false | true | 약 4분 |
| 4 | false | false | 약 4분 |

- 서버 PREPARE + 캐시 미사용 조합만 현저히 느림
- 나머지 3개 설정은 큰 차이 없음
- 단순 쿼리에서는 파싱 비용이 크지 않기 때문으로 추정

## 2. 테스트 목적

MySQL JDBC 옵션 중 다음 두 속성의 조합에 따른 성능 차이를 비교한다.

- useServerPrepStmts
- cachePrepStmts

기존 단순 쿼리 테스트에서는 일부 케이스(서버 PREPARE + 캐시 미사용)를 제외하고는 유의미한 성능 차이가 나타나지 않았다.

이에 따라 쿼리 복잡도가 증가할 경우 성능 차이가 발생하는지 검증하고자 한다.

## 3. 테스트 가설

### 가설
테스트 쿼리가 복잡해질수록 `useServerPrepStmts=true` + `cachePrepStmts=true` 조합이 가장 좋은 성능을 보일 것이다.

### 가설의 근거
- 쿼리가 복잡해질수록 SQL 파싱 비용 증가
- 바인딩 파라미터 수 증가 -> SQL 파싱 및 실행 계획 생성 비용 증가
- 서버 PREPARE 재사용 시 파싱/플랜 비용 감소 기대

## 4. 복잡한 쿼리의 정의

### 1) 조인 등 쿼리 연산 증가

```sql
SELECT
    c.customer_id,
    CONCAT(c.first_name, ' ', c.last_name) AS customer_name,
    co.country,
    COUNT(DISTINCT r.rental_id)            AS total_rentals,
    COUNT(DISTINCT f.film_id)              AS distinct_films,
    AVG(f.rental_rate)                     AS avg_rental_rate,
    SUM(p.amount)                          AS total_payment,
    GROUP_CONCAT(DISTINCT cat.name ORDER BY cat.name SEPARATOR ', ') AS preferred_categories
FROM customer c
JOIN address a      ON c.address_id   = a.address_id
JOIN city ci        ON a.city_id      = ci.city_id
JOIN country co     ON ci.country_id  = co.country_id
JOIN rental r       ON c.customer_id  = r.customer_id
JOIN inventory inv  ON r.inventory_id = inv.inventory_id
JOIN film f         ON inv.film_id    = f.film_id
JOIN film_category fc ON f.film_id   = fc.film_id
JOIN category cat   ON fc.category_id = cat.category_id
JOIN payment p      ON r.rental_id   = p.rental_id
WHERE c.customer_id = ?
GROUP BY c.
```

### 2) 동적 바인딩 파라미터 개수 증가

```sql
SELECT * FROM customer WHERE (customer_id= ? OR address_id= ?) AND active= ?
```

## 5. 결과

### 1) 조인 등 쿼리 연산 증가
| 번호  | useServerPrepStmts | cachePrepStmts | 실행시간    |
| --- | ------------------ | -------------- | ------- |
| 1   | true               | true           | 9994ms  |
| 2   | true               | false          | 11781ms |
| 3   | false              | true           | 8914ms  |
| 4   | false              | false          | 8289ms  |

### 2) 동적 바인딩 파라미터 개수 증가

| 번호 | useServerPrepStmts | cachePrepStmts | 실행시간 |
| --- | --- | --- | --- |
| 1 | true | true | 1340ms |
| 2 | true | false | 3139ms |
| 3 | false | true | 1760ms |
| 4 | false | false | 1737ms |

결과를 이해하기 위해 MySQL Connector/J 8.4.0 소스코드를 직접 디버깅했다.

### 두 방식의 실제 동작

`pstmt.executeQuery()` 호출 시 두 방식은 서로 다른 패킷을 만들어 서버로 전송한다.

**useServerPrepStmts=false — buildComQuery()**

```java
packet.writeInteger(IntegerDataType.INT1, NativeConstants.COM_QUERY);
// SQL 조각 + 값을 이어붙여서 텍스트로 씀
sendPacket.writeBytes(..., staticSqlStrings[i]);
bindValues[i].writeAsText(sendPacket);
```

`?` 기준으로 쪼개진 SQL 조각에 값을 끼워넣는 단순 문자열 연산이다.  
완성된 SQL 문자열을 `COM_QUERY` 하나로 전송한다.

**useServerPrepStmts=true — buildComStmtExecute()**

```java
packet.writeInteger(IntegerDataType.INT1, NativeConstants.COM_STMT_EXECUTE);
packet.writeInteger(IntegerDataType.INT4, serverStatementId);
packet.writeInteger(IntegerDataType.INT1, flags);
packet.writeInteger(IntegerDataType.INT4, 1);
// null 비트맵 구성
// 타입 정보 전송 여부 판단
// 값을 바이너리로 씀
parameterBindings[i].writeAsBinary(packet);
```

null 비트맵 계산, 타입 정보 판단, 바이너리 직렬화 등 패킷 구성이 훨씬 복잡하다.

### 캐시가 동작하면 통신 횟수는 같다

`cachePrepStmts=true`로 PREPARE가 캐시된 이후에는 통신 횟수가 동일하다.

```
false: COM_QUERY        → 1회
true:  COM_STMT_EXECUTE → 1회 (캐시 히트 이후)
```

통신 횟수가 같음에도 성능 차이가 나타난다면, 패킷 구성 복잡도 차이가 영향을 줄 것으로 추정된다.

### true + false가 항상 가장 느린 이유

캐시를 사용하지 않으면 매 반복마다 `COM_STMT_PREPARE`와 `COM_STMT_CLOSE`가 추가로 발생한다. 이 오버헤드가 누적되어 일관되게 가장 낮은 성능을 보였다.

## 6. 결론

단일 Connection에서 동일 쿼리를 반복하는 구조상, 네 설정 모두 실제 데이터 처리 비용은 동일하다. 설정 차이가 영향을 주는 영역은 파싱 및 Prepared 단계뿐이며, 이 비용이 전체 실행 시간에서 차지하는 비중이 작아 유의미한 차이가 나타나지 않았을 가능성이 높다.

소스코드 분석으로 패킷 구성 복잡도 차이를 확인했으나, 성능 차이의 직접적인 원인으로 단정하기는 어렵다. 쿼리 특성에 따라 결과가 달라졌기 때문이다.

| 테스트 | 가장 빠른 설정 |
|--------|--------------|
| 조인 등 쿼리 연산 증가 | `false + false` (8289ms) |
| 동적 바인딩 파라미터 증가 | `true + true` (1340ms) |

본 실험은 단일 Connection, 단일 쿼리 환경으로 제한적이다. 다중 Connection, 다양한 쿼리가 존재하는 실제 운영 환경에서는 다른 결과가 나타날 수 있다.

**최적 설정은 실제 서비스 환경에서 직접 벤치마크하는 것 외에 정답이 없다.**
