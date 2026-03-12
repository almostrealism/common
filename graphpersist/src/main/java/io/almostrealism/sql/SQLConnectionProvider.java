package io.almostrealism.sql;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Provides access to a SQL database connection. A production implementation
 * should consider connection pooling rather than returning raw connections.
 */
public interface SQLConnectionProvider {
	Connection getSQLConnection() throws SQLException;
}
