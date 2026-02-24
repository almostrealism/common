/*
 * Copyright 2016 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.sql;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import io.almostrealism.query.SimpleUpdate;
import org.apache.commons.beanutils.BeanUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

/**
 * @author  Michael Murray
 */
public class SQLInsert<V> extends SimpleUpdate<ComboPooledDataSource, String[], V> {

	/**
	 * Construct a new SQLInsert. Used by the static method {@link #prepare(String, Properties)}.
	 *
	 * @param query  The SQL query to execute.
	 * @param columns  The mapping between columns in the database and field names.
	 */
	private SQLInsert(String query, Properties columns) {
		super(query);
		for (String n : columns.stringPropertyNames()) put(n, columns.getProperty(n));
	}

	/**
	 * Execute the update against the database using a {@link Connection} from the
	 * specified pooled data source.
	 *
	 * @param database
	 * @param keys
	 * @return
	 * @throws InvocationTargetException 
	 * @throws IllegalAccessException 
	 * @throws NoSuchMethodException 
	 */
	public void execute(ComboPooledDataSource database, String[] keys, V value) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
		String[] splitOnWhereClause = query.split("[w,W][h,H][e,E][r,R][e,E]");
		
		StringBuffer q = new StringBuffer();
		q.append(splitOnWhereClause[0]);
		q.append("set ");
		
		Iterator<Map.Entry> itr = iterator();
		
		while (itr.hasNext()) {
			q.append((String) itr.next().getValue());
			q.append("=?");
			if (itr.hasNext()) q.append(",");
		}
		
		q.append(" where");
		q.append(splitOnWhereClause[1]);
		
		try (Connection c = database.getConnection();
				PreparedStatement s = c.prepareStatement(q.toString())) {

			int index = 0;
			
			// Plug in the values to the SET portion
			for (Map.Entry ent : this) {
				s.setString(index + 1, BeanUtils.getProperty(value, (String) ent.getKey()));
				index++;
			}
			
			// Plug in the keys to the where clause
			for (int i = 0; i < keys.length; i++) {
				s.setString(index + i + 1, keys[i]);
			}
			
			s.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public static <V> SQLInsert<V> prepare(String query, Properties columns) {
		return new SQLInsert(query, columns);
	}
	
	public static <V> SQLInsert<V> prepare(String query, InputStream columnMap) throws IOException {
		Properties fieldMap = new Properties();
		fieldMap.load(columnMap);
		return prepare(query, fieldMap);
	}
	
	/** Select all. */
	public static <V> SQLInsert<V> prepare(InputStream columnMap) throws IOException {
		return prepare("select *", columnMap);
	}
}
