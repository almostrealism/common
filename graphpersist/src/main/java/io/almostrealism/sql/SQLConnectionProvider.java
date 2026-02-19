package io.almostrealism.sql;

import java.sql.Connection;
import java.sql.SQLException;

// TODO  This should provide a pool rather than a connection
/** The SQLConnectionProvider interface. */
public interface SQLConnectionProvider {
	/** Performs the getSQLConnection operation. */
	Connection getSQLConnection() throws SQLException;
}
