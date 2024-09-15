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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.hardware.OperationList;

import java.util.Optional;
import java.util.function.Supplier;

public abstract class CellAdapter<T> implements Cell<T> {
	private Receptor<T> r;
	private Receptor<T> meter;
	
	private String name;
	
	public void setName(String n) { this.name = n; }
	
	public String getName() { return this.name; }

	@Override
	public void setReceptor(Receptor<T> r) {
		if (cellWarnings && this.r != null) {
			CollectionFeatures.console.features(CellAdapter.class)
					.warn("Replacing receptor");
		}

		this.r = r;
	}
	
	public Receptor<T> getReceptor() { return this.r; }
	
	public void setMeter(Receptor<T> m) { this.meter = m; }
	
	/** Push to the {@link Receptor}. */
	@Override
	public Supplier<Runnable> push(Producer<T> protein) {
		OperationList push = new OperationList("CellAdapter Push");
		if (meter != null) push.add(meter.push(protein));
		if (r != null) push.add(r.push(protein));
		return push;
	}

	@Override
	public String toString() {
		String className = getClass().getSimpleName();
		return Optional.ofNullable(name)
				.map(s -> s + " (" + className + ")")
				.orElse(className);
	}
}
