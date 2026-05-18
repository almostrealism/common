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

/**
 * Input/Output utilities for the computation graph.
 *
 * <p>This package provides components for reading and writing data to/from
 * the computation graph, including file-based receptors for logging and
 * data export.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.graph.io.CSVReceptor} - Writes received values
 *       to a CSV output stream for logging training metrics or exporting data</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Log training loss to a CSV file
 * try (CSVReceptor<Double> receptor =
 *         new CSVReceptor<>(new FileOutputStream("loss.csv"), 100)) {
 *     optimizer.setReceptor(receptor);
 *     optimizer.optimize(epochs);
 * }
 * }</pre>
 *
 * @author Michael Murray
 */
package org.almostrealism.graph.io;