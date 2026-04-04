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
 * A {@link SimpleQuery} implementation that executes a parameterized SQL SELECT statement
 * against a c3p0 pooled data source and maps each result row to a new entity instance.
 *
 * <p>Positional parameters ({@code ?}) in the query are filled with the supplied string
 * arguments. Each result column is reflectively set on the entity via BeanUtils using the
 * registered column-to-field mapping.</p>
 *
 * @param <V> The entity type produced from each result row
 *
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

	/**
	 * Creates a new {@link SQLSelect} with the given SQL query, column mapping, and factory.
	 *
	 * @param <V>     The entity type
	 * @param query   The SQL SELECT statement template (may include {@code ?} parameters)
	 * @param columns A {@link Properties} map from field names to column names
	 * @param factory Factory for creating new entity instances
	 * @return A new {@link SQLSelect} for the given query and mappings
	 */
	public static <V> SQLSelect<V> prepare(String query, Properties columns, Factory<V> factory) {
		return new SQLSelect(query, columns, factory);
	}

	/**
	 * Creates a new {@link SQLSelect} by loading the column mapping from the given input stream.
	 *
	 * @param <V>       The entity type
	 * @param query     The SQL SELECT statement template
	 * @param columnMap An input stream for a {@link Properties} file mapping field names to columns
	 * @param factory   Factory for creating new entity instances
	 * @return A new {@link SQLSelect} for the given query and mappings
	 * @throws IOException If loading the properties file fails
	 */
	public static <V> SQLSelect<V> prepare(String query, InputStream columnMap, Factory<V> factory) throws IOException {
		Properties fieldMap = new Properties();
		fieldMap.load(columnMap);
		return prepare(query, fieldMap, factory);
	}

	/**
	 * Creates a new {@link SQLSelect} with a {@code "select *"} query using the column mapping
	 * loaded from the given input stream.
	 *
	 * @param <V>       The entity type
	 * @param columnMap An input stream for a {@link Properties} file mapping field names to columns
	 * @param factory   Factory for creating new entity instances
	 * @return A new {@link SQLSelect} with a select-all query
	 * @throws IOException If loading the properties file fails
	 */
	public static <V> SQLSelect<V> prepare(InputStream columnMap, Factory<V> factory) throws IOException {
		return prepare("select *", columnMap, factory);
	}
}
