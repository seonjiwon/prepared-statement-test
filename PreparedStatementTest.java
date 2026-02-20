package dev.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PreparedStatementBenchmark {

	private static final String USER_NAME = "root";
	private static final String PASSWORD = "";
	private static final String DB_URL = "jdbc:mysql://localhost:3306/sakila";

	private static Connection connection;
	private static PreparedStatement pstmt;
	private static ResultSet rs;

	private static long executeBenchmark(String properties, String sql, int loopCount, boolean dynamicBinding) throws SQLException {
		long start;
		long end;

		try (connection = DriverManager.getConnection(DB_URL + properties, USER_NAME, PASSWORD)) {
			start = System.currentTimeMillis();

			for (int i = 0; i < loopCount; i++) {
				try (pstmt = connection.prepareStatement(sql)) {

					if (dynamicBinding) {
						pstmt.setLong(1, i + 1);
						pstmt.setLong(2, i + 1);
						pstmt.setInt(3, (i % 2));
					} else {
						pstmt.setInt(1, (i % 599) + 1);
					}
					rs = pstmt.executeQuery();

					rs.close();
					pstmt.close();
				}
			}
			end = System.currentTimeMillis();
		}
		return end - start;
	}

	/**
	 * 1. 조인 쿼리 테스트
	 */
	public static long testJoinQuery(boolean useServerPrepStmts, boolean cachePrepStmts) throws SQLException {
		String properties = String.format("?useServerPrepStmts=%s&cachePrepStmts=%s", useServerPrepStmts, cachePrepStmts);

		String sql = """
                SELECT
                    c.customer_id,
                    CONCAT(c.first_name, ' ', c.last_name) AS customer_name,
                    co.country,
                    COUNT(DISTINCT r.rental_id) AS total_rentals,
                    COUNT(DISTINCT f.film_id) AS distinct_films,
                    AVG(f.rental_rate) AS avg_rental_rate,
                    SUM(p.amount) AS total_payment
                FROM customer c
                JOIN address a ON c.address_id = a.address_id
                JOIN city ci ON a.city_id = ci.city_id
                JOIN country co ON ci.country_id = co.country_id
                JOIN rental r ON c.customer_id = r.customer_id
                JOIN inventory inv ON r.inventory_id = inv.inventory_id
                JOIN film f ON inv.film_id = f.film_id
                JOIN payment p ON r.rental_id = p.rental_id
                WHERE c.customer_id = ?
                GROUP BY c.customer_id, c.first_name, c.last_name, co.country
                """;

		return executeBenchmark(properties, sql, 20000, false);
	}

	/**
	 * 2. 동적 바인딩 파라미터 증가 테스트
	 */
	public static long testDynamicBinding(boolean useServerPrepStmts, boolean cachePrepStmts) throws SQLException {
		String properties = String.format("?useServerPrepStmts=%s&cachePrepStmts=%s", useServerPrepStmts, cachePrepStmts);

		String sql = "SELECT * FROM customer WHERE (customer_id = ? OR address_id = ?) AND active = ?";

		return executeBenchmark(properties, sql, 20000, true);
	}
}

PreparedStatementBenchmark.testJoinQuery(true, true);
//PreparedStatementBenchmark.testJoinQuery(true, false);
//PreparedStatementBenchmark.testJoinQuery(false, true);
//PreparedStatementBenchmark.testJoinQuery(false, false);
//PreparedStatementBenchmark.testDynamicBinding(true, true);
//PreparedStatementBenchmark.testDynamicBinding(true, false);
//PreparedStatementBenchmark.testDynamicBinding(false, true);
//PreparedStatementBenchmark.testDynamicBinding(false, false);