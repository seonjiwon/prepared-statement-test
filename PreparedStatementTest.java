package dev.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PreparedStatementTest {
	private static final String USER_NAME = "root";
	private static final String PASSWORD = "";
	private static final String DB_URL = "jdbc:mysql://localhost:3306/sakila";

	private static Connection connection;
	private static PreparedStatement pstmt;
	private static ResultSet rs;

	public static void main(String[] args) {

		try {
			String properties = "?useServerPrepStmts=true &cachePrepStmts=true";
//          String properties = "?useServerPrepStmts=true&cachePrepStmts=false";
//			String properties = "?useServerPrepStmts=false&cachePrepStmts=true";
//			String properties = "?useServerPrepStmts=false&cachePrepStmts=false";

			connection = DriverManager.getConnection(DB_URL + properties, USER_NAME, PASSWORD);

//			String sql = """
//		                SELECT
//		                    c.customer_id,
//		                    CONCAT(c.first_name, ' ', c.last_name) AS customer_name,
//		                    co.country,
//		                    COUNT(DISTINCT r.rental_id)            AS total_rentals,
//		                    COUNT(DISTINCT f.film_id)              AS distinct_films,
//		                    AVG(f.rental_rate)                     AS avg_rental_rate,
//		                    SUM(p.amount)                          AS total_payment,
//		                    GROUP_CONCAT(DISTINCT cat.name ORDER BY cat.name SEPARATOR ', ') AS preferred_categories
//		                FROM customer c
//		                JOIN address a      ON c.address_id   = a.address_id
//		                JOIN city ci        ON a.city_id      = ci.city_id
//		                JOIN country co     ON ci.country_id  = co.country_id
//		                JOIN rental r       ON c.customer_id  = r.customer_id
//		                JOIN inventory inv  ON r.inventory_id = inv.inventory_id
//		                JOIN film f         ON inv.film_id    = f.film_id
//		                JOIN film_category fc ON f.film_id   = fc.film_id
//		                JOIN category cat   ON fc.category_id = cat.category_id
//		                JOIN payment p      ON r.rental_id   = p.rental_id
//		                WHERE c.customer_id = ?
//		                GROUP BY c.customer_id, c.first_name, c.last_name, co.country
//					""";

			String sql = "SELECT * FROM customer WHERE (customer_id = ? OR address_id = ?) AND active = ?";

			long start = System.currentTimeMillis();

//			for (int i = 0; i < 20000; i++) {
//				PreparedStatement pstmt = connection.prepareStatement(sql);
//				pstmt.setInt(1, (i % 599) + 1);
//				ResultSet rs = pstmt.executeQuery();
//				rs.close();
//				pstmt.close();
//			}

			for (int i = 0; i < 20000; i++) {
				pstmt = connection.prepareStatement(sql);
				pstmt.setLong(1, i + 1);
				pstmt.setLong(2, i + 1);
				pstmt.setInt(3, i + 1);
				rs = pstmt.executeQuery();

				rs.close();
				pstmt.close();
			}

			long end = System.currentTimeMillis();
			System.out.println("실행시간: " + (end - start) + "ms");

		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (connection != null)
					connection.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}
}