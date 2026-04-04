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
 * Generic database query and update abstractions for structured data access.
 *
 * <p>This package provides database-agnostic interfaces and implementations for
 * reading and writing POJOs from any structured data source:</p>
 * <ul>
 *   <li>{@link io.almostrealism.query.Query} — Interface for retrieving objects from any database</li>
 *   <li>{@link io.almostrealism.query.Update} — Interface for writing objects to any database</li>
 *   <li>{@link io.almostrealism.query.QueryLibrary} — Registry mapping object types to their queries</li>
 *   <li>{@link io.almostrealism.query.UpdateLibrary} — Registry mapping object types to their updates</li>
 *   <li>{@link io.almostrealism.query.SimpleQuery} — Column-to-field mapping skeleton for query implementations</li>
 *   <li>{@link io.almostrealism.query.SimpleUpdate} — Column-to-field mapping skeleton for update implementations</li>
 * </ul>
 *
 * @see io.almostrealism.persist
 * @see io.almostrealism.sql
 */
package io.almostrealism.query;
