package io.almostrealism.etl;

import io.almostrealism.sql.SQLConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;

public abstract class View<V> {
	private final SQLConnectionProvider sql;
	private final String table;
	private final Collection<V> values;
	
	public View(SQLConnectionProvider c, String table, Collection<V> values) {
		this.sql = c;
		this.table = table;
		this.values = values;
	}
	
	public abstract Map<String, String> encode(V value);
	
	public abstract String[] getPrimaryKeys();
	
	public void process() throws SQLException {
		try (Connection c = sql.getSQLConnection(); Statement s = c.createStatement()) {
			for (V v : values) {
				Map<String, String> data = encode(v);
				s.executeUpdate(getDelete(data));
				s.executeUpdate(getInsert(data));
			}
		}
	}
	
	protected String quote(String s) { return "'" + s.replaceAll("'", "''") + "'"; }
	
	protected String getDelete(Map<String, String> data) {
		StringBuffer buf = new StringBuffer();
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
	
	private String getValueList(String[] values) {
		StringBuffer buf = new StringBuffer();
		
		buf.append("(");
		
		for (int i = 0; i < values.length; i++) {
			buf.append(values[i]);
			if (i < (values.length - 1)) buf.append(",");
		}
		
		buf.append(")");
		
		return buf.toString();
	}
}
