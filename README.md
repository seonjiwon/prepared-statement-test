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

단순 쿼리에서는 파싱 비용이 작기 때문에 설정 간 차이가 크지 않았다고 판단하였고, 이를 검증하기 위해 조인 및 집계가 포함된 복잡한 쿼리로 테스트를 확장하였다. 그러나 **복잡한 쿼리에서도 결과는 기존 테스트와 유사한 경향을 보였다.**

이는 동일한 쿼리를 단일 Connection 환경에서 반복 실행하는 구조에서, 네 가지 설정 모두 실제 데이터 처리 비용이 거의 동일하게 발생하기 때문으로 추정하고 있다.

**설정에 따라 달라질 수 있는 영역은 파싱 및 Prepared 단계에 한정되지만, 해당 비용이 전체 실행 시간에서 차지하는 비중이 크지 않았기 때문**에 useServerPrepStmts 및 cachePrepStmts 조합에 따른 차이가 유의미하게 나타나지 않았을 가능성이 높다.

다만, 서버 PREPARE를 사용하면서 캐시를 사용하지 않는 조합은 매 반복마다 PREPARE 및 DEALLOCATE가 수행되므로 추가적인 오버헤드가 발생하며, 이로 인해 일관되게 가장 낮은 성능을 보였다고 추정한다.

결과적으로 본 실험 환경에서는 서버 PreparedStatement 재사용의 이점이 제한적으로 나타났으며, 실제 운영 환경과 같이 다중 Connection, 다양한 쿼리 등이 존재하는 상황에서는 다른 결과가 나타날 가능성도 있다.
