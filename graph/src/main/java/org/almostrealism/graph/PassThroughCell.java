/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class PassThroughCell<T> implements Cell<T> {
	private Receptor<T> r;

	@Override
	public Supplier<Runnable> setup() { return new OperationList("PassThroughCell Setup"); }

	@Override
	public Supplier<Runnable> push(Producer<T> protein) { return r.push(protein); }

	@Override
	public void setReceptor(Receptor<T> r) {
		if (cellWarnings && this.r != null) {
			CollectionFeatures.console.features(PassThroughCell.class)
					.warn("Replacing receptor");
		}

		this.r = r;
	}
}
