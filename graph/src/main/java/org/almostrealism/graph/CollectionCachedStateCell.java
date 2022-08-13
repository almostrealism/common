/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareFeatures;

import java.util.function.Supplier;

public class CollectionCachedStateCell extends CachedStateCell<PackedCollection<?>> implements HardwareFeatures {
	public CollectionCachedStateCell() {
		super(PackedCollection.blank(1).get());
	}

	@Override
	protected Supplier<Runnable> assign(Supplier<Evaluable<? extends PackedCollection<?>>> out, Supplier<Evaluable<? extends PackedCollection<?>>> in) {
		return a(1, out, in);
	}

	@Override
	public Supplier<Runnable> reset(Supplier<Evaluable<? extends PackedCollection<?>>> out) {
		return a(1, out, PackedCollection.blank(1));
	}
}
