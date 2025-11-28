/*
 * Copyright 2020 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.graph;
import org.almostrealism.collect.PackedCollection;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.hardware.OperationList;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * A base implementation of the {@link Cell} interface that provides common functionality
 * for cell implementations. This abstract class handles receptor management, metering,
 * and standard push behavior.
 *
 * <p>CellAdapter simplifies creating custom cells by providing:</p>
 * <ul>
 *   <li>Receptor storage and management</li>
 *   <li>Optional metering receptor for monitoring/debugging</li>
 *   <li>A standard push implementation that forwards to both meter and receptor</li>
 *   <li>Named cells with useful toString representation</li>
 * </ul>
 *
 * <h2>Metering</h2>
 * <p>CellAdapter supports an optional "meter" receptor that receives a copy of all
 * pushed data. This is useful for monitoring, debugging, or logging data flow:</p>
 *
 * <pre>{@code
 * CellAdapter<PackedCollection> cell = new MyCellAdapter();
 * cell.setMeter(data -> {
 *     System.out.println("Data flowing through: " + data);
 *     return new OperationList();
 * });
 * }</pre>
 *
 * <h2>Extending CellAdapter</h2>
 * <p>Subclasses typically override these methods:</p>
 * <ul>
 *   <li>{@link #setup()} - For initialization logic</li>
 *   <li>{@link #push(Producer)} - For custom transformation logic (call super to forward)</li>
 * </ul>
 *
 * @param <T> the type of data processed by this cell, typically {@link org.almostrealism.collect.PackedCollection}
 * @see Cell
 * @see FilteredCell
 * @author Michael Murray
 */
public abstract class CellAdapter<T> implements Cell<T> {
	private Receptor<T> r;
	private Receptor<T> meter;

	private String name;

	/**
	 * Sets a human-readable name for this cell.
	 *
	 * @param n the name to assign to this cell
	 */
	public void setName(String n) { this.name = n; }

	/**
	 * Returns the name of this cell.
	 *
	 * @return the cell's name, or null if not set
	 */
	public String getName() { return this.name; }

	/**
	 * Sets the downstream receptor that will receive this cell's output.
	 * Logs a warning if replacing an existing receptor when cell warnings are enabled.
	 *
	 * @param r the receptor to receive output from this cell
	 */
	@Override
	public void setReceptor(Receptor<T> r) {
		if (cellWarnings && this.r != null) {
			CollectionFeatures.console.features(CellAdapter.class)
					.warn("Replacing receptor");
		}

		this.r = r;
	}

	/**
	 * Returns the current downstream receptor.
	 *
	 * @return the receptor receiving this cell's output, or null if none set
	 */
	public Receptor<T> getReceptor() { return this.r; }

	/**
	 * Sets an optional metering receptor that receives copies of all pushed data.
	 * The meter receives data before the main receptor, useful for monitoring
	 * or debugging data flow.
	 *
	 * @param m the metering receptor, or null to disable metering
	 */
	public void setMeter(Receptor<T> m) { this.meter = m; }

	/**
	 * Pushes data to both the meter (if set) and the main receptor (if set).
	 * This implementation first pushes to the meter, then to the receptor.
	 *
	 * <p>Subclasses typically override this method to add transformation logic
	 * before calling super.push() to forward the result.</p>
	 *
	 * @param protein the data producer to push through this cell
	 * @return a supplier of operations that will push the data when executed
	 */
	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		OperationList push = new OperationList("CellAdapter Push");
		if (meter != null) push.add(meter.push(protein));
		if (r != null) push.add(r.push(protein));
		return push;
	}

	/**
	 * Returns a string representation of this cell, including its name if set.
	 *
	 * @return "name (ClassName)" if named, otherwise just the class name
	 */
	@Override
	public String toString() {
		String className = getClass().getSimpleName();
		return Optional.ofNullable(name)
				.map(s -> s + " (" + className + ")")
				.orElse(className);
	}
}
