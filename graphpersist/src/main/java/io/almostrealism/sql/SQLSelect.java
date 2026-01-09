package io.almostrealism.sql;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import io.almostrealism.persist.CascadingQuery;
import io.almostrealism.query.SimpleQuery;
import io.almostrealism.relation.Factory;
import org.apache.commons.beanutils.BeanUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @author  Michael Murray
 */
public class SQLSelect<V> extends SimpleQuery<ComboPooledDataSource, String[], V> {

	/**
	 * Construct a new SQLSelect. Used by the static method {@link #prepare(String, Properties)}.
	 *
	 * @param query  The SQL query to execute.
	 * @param columns  The mapping between columns in the database and field names.
	 */
	private SQLSelect(String query, Properties columns, Factory<V> factory) {
		super(query, factory);
		for (String n : columns.stringPropertyNames()) put(n, columns.getProperty(n));
	}

	/**
	 * Execute the query against the database using a {@link Connection} from the
	 * specified pooled data source.
	 *
	 * @param database
	 * @param arguments
	 * @return
	 * @throws InvocationTargetException 
	 * @throws IllegalAccessException 
	 */
	public Collection<V> execute(ComboPooledDataSource database, String[] arguments, Map<Class, List<CascadingQuery>> cascades) throws IllegalAccessException, InvocationTargetException {
		List<V> data = new ArrayList<V>();
		
		try (Connection c = database.getConnection();
				PreparedStatement s = c.prepareStatement(query)) {
			
			if (arguments != null) {
				for (int i = 0; i < arguments.length; i++) {
					s.setString(i + 1, arguments[i]);
				}
			}
			
			ResultSet rs = s.executeQuery();
			
			while (rs.next()) {
				V entity = createEntity();
				
				for (Map.Entry ent : this) {
					BeanUtils.setProperty(entity, (String) ent.getKey(),
									rs.getString((String) ent.getValue()));
				}
				
				if (!data.contains(entity)) {
					data.add(entity);
				}
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}

		return data;
	}

	public static <V> SQLSelect<V> prepare(String query, Properties columns, Factory<V> factory) {
		return new SQLSelect(query, columns, factory);
	}
	
	public static <V> SQLSelect<V> prepare(String query, InputStream columnMap, Factory<V> factory) throws IOException {
		Properties fieldMap = new Properties();
		fieldMap.load(columnMap);
		return prepare(query, fieldMap, factory);
	}
	
	/** Select all. */
	public static <V> SQLSelect<V> prepare(InputStream columnMap, Factory<V> factory) throws IOException {
		return prepare("select *", columnMap, factory);
	}
}
