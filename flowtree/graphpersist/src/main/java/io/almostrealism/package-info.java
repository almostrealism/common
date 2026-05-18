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
 * Top-level entry point for the GraphPersist module.
 *
 * <p>The {@link io.almostrealism.GraphPersist} class provides a facade for persisting
 * and retrieving {@link org.almostrealism.collect.PackedCollection} data to a relational
 * database. By default it starts an embedded HSQLDB instance when no external database
 * is configured via system properties. Configured databases are accessed through the
 * {@link io.almostrealism.db.DatabaseConnection} layer.</p>
 *
 * @see io.almostrealism.db
 * @see io.almostrealism.resource
 */
package io.almostrealism;
