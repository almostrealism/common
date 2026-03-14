package io.almostrealism.sql;

import java.sql.Connection;
import java.sql.SQLException;

// TODO  This should provide a pool rather than a connection
public interface SQLConnectionProvider {
	Connection getSQLConnection() throws SQLException;
}
