package io.almostrealism.etl;

import io.almostrealism.sql.SQLConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;

/**
 * Abstract base class for an ETL view that maps a collection of values to rows in a
 * relational database table via delete-then-insert upsert semantics.
 *
 * <p>Subclasses implement {@link #encode(Object)} to convert a value to a column-name-to-value
 * map, and {@link #getPrimaryKeys()} to identify which columns form the primary key used in the
 * delete clause. Calling {@link #process()} executes the full load in a single JDBC statement.</p>
 *
 * @param <V> The type of values loaded into the database table
 */
public abstract class View<V> {
	/** The SQL connection provider used to obtain JDBC connections. */
	private final SQLConnectionProvider sql;
	/** The name of the database table into which values are loaded. */
	private final String table;
	/** The collection of values to process during {@link #process()}. */
	private final Collection<V> values;

	/**
	 * Constructs a {@link View} that will load the given values into the specified table.
	 *
	 * @param c      The SQL connection provider
	 * @param table  The target database table name
	 * @param values The collection of values to load
	 */
	public View(SQLConnectionProvider c, String table, Collection<V> values) {
		this.sql = c;
		this.table = table;
		this.values = values;
	}
	
	/**
	 * Encodes a value as a map of column names to their SQL string representations.
	 *
	 * @param value The value to encode
	 * @return A map from column name to SQL-ready string value
	 */
	public abstract Map<String, String> encode(V value);

	/**
	 * Returns the column names that form the primary key for this table, used
	 * to construct the {@code DELETE} statement before each insert.
	 *
	 * @return An array of primary key column names
	 */
	public abstract String[] getPrimaryKeys();

	/**
	 * Processes all values by executing a delete-then-insert for each one
	 * within a single JDBC {@link Statement}.
	 *
	 * @throws SQLException If any SQL statement fails during processing
	 */
	public void process() throws SQLException {
		try (Connection c = sql.getSQLConnection(); Statement s = c.createStatement()) {
			for (V v : values) {
				Map<String, String> data = encode(v);
				s.executeUpdate(getDelete(data));
				s.executeUpdate(getInsert(data));
			}
		}
	}
	
	/**
	 * Wraps a string in single quotes, escaping any interior single quotes,
	 * for safe embedding in SQL string literals.
	 *
	 * @param s The string to quote
	 * @return The SQL-quoted string
	 */
	protected String quote(String s) { return "'" + s.replaceAll("'", "''") + "'"; }

	/**
	 * Builds a {@code DELETE} statement that removes rows matching the primary key values
	 * found in the given data map.
	 *
	 * @param data The encoded column-to-value map for the current row
	 * @return A SQL {@code DELETE} statement string
	 */
	protected String getDelete(Map<String, String> data) {
		StringBuilder buf = new StringBuilder();
		buf.append("delete from ");
		buf.append(table);
		buf.append(" where ");
		
		String[] where = getPrimaryKeys();
		
		for (int i = 0; i < where.length; i++) {
			buf.append(where[i]);
			buf.append(" = ");
			buf.append(data.get(where[i]));
			
			if (i < (where.length - 1)) {
				buf.append(" and ");
			}
		}
		
		return buf.toString();
	}
	
	/**
	 * Builds an {@code INSERT INTO} statement for the given data map.
	 *
	 * @param data The encoded column-to-value map for the current row
	 * @return A SQL {@code INSERT} statement string
	 */
	protected String getInsert(Map<String, String> data) {
		String[] names = new String[data.size()];
		String[] values = new String[data.size()];
		
		int i = 0;
		for (Map.Entry<String, String> m : data.entrySet()) {
			names[i] = m.getKey();
			values[i] = m.getValue();
			i++;
		}

		String buf = "insert into " +
				table +
				" " +
				getValueList(names) +
				" values " +
				getValueList(values);
		return buf;
	}
	
	/**
	 * Formats an array of strings as a parenthesized, comma-separated SQL value list.
	 *
	 * @param values The string values to format
	 * @return A SQL value list of the form {@code (v1,v2,...)}
	 */
	private String getValueList(String[] values) {
		StringBuilder buf = new StringBuilder();
		
		buf.append("(");
		
		for (int i = 0; i < values.length; i++) {
			buf.append(values[i]);
			if (i < (values.length - 1)) buf.append(",");
		}
		
		buf.append(")");
		
		return buf.toString();
	}
}
