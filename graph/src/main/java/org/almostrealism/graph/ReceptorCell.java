/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.relation.Producer;
import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class ReceptorCell<T> implements Cell<T> {
	private Receptor<T> r;

	public ReceptorCell(Receptor<T> r) { this.r = r; }

	@Override
	public Supplier<Runnable> setup() { return new OperationList("ReceptorCell Setup"); }

	@Override
	public Supplier<Runnable> push(Producer<T> protein) { return r.push(protein); }

	@Override
	public void setReceptor(Receptor<T> r) { this.r = r; }

	public Receptor<T> getReceptor() { return r; }

	@Override
	public void reset() {
		Cell.super.reset();
		if (r instanceof Lifecycle) {
			((Lifecycle) r).reset();
		}
	}
}
