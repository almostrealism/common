/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.code.Setup;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Lifecycle;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public interface Cell<T> extends Transmitter<T>, Receptor<T>, Setup, Lifecycle {
	static <T> Cell<T> from(Producer<T> p) {
		return new Cell<T>() {
			private Receptor<T> r;

			@Override
			public Supplier<Runnable> setup() {
				return new OperationList();
			}

			@Override
			public Supplier<Runnable> push(Producer<T> protein) {
				return r == null ? new OperationList() : r.push(p);
			}

			@Override
			public void setReceptor(Receptor<T> r) {
				this.r = r;
			}
		};
	}
}
