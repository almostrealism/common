package io.almostrealism.sql;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Service interface for obtaining a JDBC {@link Connection}.
 *
 * <p>Implementations may back this with a single connection, a c3p0 pool, or any other
 * connection source. Callers are responsible for closing the returned connection when done.</p>
 *
 * <p>TODO: This should provide a pool rather than a raw connection.</p>
 */
public interface SQLConnectionProvider {
	/**
	 * Returns a JDBC {@link Connection} to the underlying database.
	 *
	 * @return A live JDBC connection
	 * @throws SQLException If a connection cannot be obtained
	 */
	Connection getSQLConnection() throws SQLException;
}
