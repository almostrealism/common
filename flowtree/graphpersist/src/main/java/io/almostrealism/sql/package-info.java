/*
 * Copyright 2025 Michael Murray
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

/**
 * SQL-backed implementations of the query and update abstractions.
 *
 * <p>This package provides concrete SQL implementations using connection pooling
 * via C3P0 and field-mapping via Apache BeanUtils:</p>
 * <ul>
 *   <li>{@link io.almostrealism.sql.SQLConnectionProvider} — Interface for obtaining SQL connections</li>
 *   <li>{@link io.almostrealism.sql.SQLSelect} — Executes a SQL SELECT and maps result columns to POJO fields</li>
 *   <li>{@link io.almostrealism.sql.SQLInsert} — Executes a SQL INSERT/UPDATE and populates values from POJO fields</li>
 * </ul>
 *
 * @see io.almostrealism.query
 */
package io.almostrealism.sql;
