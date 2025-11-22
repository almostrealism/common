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

package org.almostrealism.graph;

/**
 * A data-transmitting component in the Almost Realism computation graph architecture.
 * Transmitters are components that can send data to downstream {@link Receptor} instances.
 *
 * <p>The Transmitter interface is one half of the core cell communication pattern, with
 * {@link Receptor} being the other half. Together, they enable a push-based data flow
 * model where data propagates through connected components.</p>
 *
 * <h2>Connection Model</h2>
 * <p>A transmitter maintains a reference to its downstream receptor. When the transmitter
 * produces output (typically via a push operation inherited from another interface),
 * it forwards that output to its connected receptor.</p>
 *
 * <h2>Graph Construction</h2>
 * <p>The {@link #setReceptor(Receptor)} method is the primary mechanism for building
 * computation graphs. By connecting transmitters to receptors, you create a directed
 * graph of processing nodes:</p>
 *
 * <pre>{@code
 * // Connect cells in a pipeline
 * Cell<PackedCollection<?>> cell1 = ...;
 * Cell<PackedCollection<?>> cell2 = ...;
 * cell1.setReceptor(cell2);  // cell1's output flows to cell2
 * }</pre>
 *
 * <p>For convenience, the {@link Cell#andThen(Cell)} method provides a fluent API
 * for building pipelines.</p>
 *
 * @param <T> the type of data transmitted, typically {@link org.almostrealism.collect.PackedCollection}
 * @see Receptor
 * @see Cell
 * @author Michael Murray
 */
public interface Transmitter<T> {
	/**
	 * Sets the downstream receptor that will receive this transmitter's output.
	 *
	 * <p>When this transmitter produces output, it will be pushed to the specified
	 * receptor. Setting a new receptor replaces any previously set receptor.</p>
	 *
	 * <p>Some implementations may log a warning when replacing an existing receptor
	 * if the {@code AR_GRAPH_CELL_WARNINGS} system property is enabled.</p>
	 *
	 * @param r the receptor to receive this transmitter's output, may be null to disconnect
	 */
	void setReceptor(Receptor<T> r);
}
