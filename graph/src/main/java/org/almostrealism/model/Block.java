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

package org.almostrealism.model;

import io.almostrealism.cycle.Setup;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.layers.Component;

public interface Block extends Component, Setup {
	TraversalPolicy getInputShape();

	Cell<PackedCollection<?>> getForward();

	Cell<PackedCollection<?>> getBackward();

	default <T extends Block> T append(T l) {
		append(l.getForward());
		return l;
	}

	<T extends Receptor<PackedCollection<?>>> T append(T r);

	default <T extends Block> T andThen(T next) {
		getForward().setReceptor(next.getForward());
		return next;
	}

	default <T extends Receptor<PackedCollection<?>>> T andThen(T next) {
		getForward().setReceptor(next);
		return next;
	}
}
