/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.db;

import java.util.Hashtable;

/**
 * Service interface for executing a {@link Query} against a database and returning
 * the results as a {@link Hashtable}.
 */
public interface QueryHandler {
	/**
	 * Executes the given query and returns a {@link Hashtable} mapping key column values
	 * to their corresponding data column values.
	 *
	 * @param q The query to execute
	 * @return A hashtable of results, keyed by the query's key column
	 */
	Hashtable executeQuery(Query q);
}