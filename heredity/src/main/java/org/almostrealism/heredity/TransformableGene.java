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

package org.almostrealism.heredity;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.UnaryOperator;

public abstract class TransformableGene implements Gene<PackedCollection<?>> {

	private UnaryOperator<Producer<PackedCollection<?>>> transform;
	private UnaryOperator<Producer<PackedCollection<?>>> transforms[];

	public TransformableGene(int length) {
		this.transforms = new UnaryOperator[length];
	}

	public UnaryOperator<Producer<PackedCollection<?>>> getTransform() {
		return transform;
	}

	public void setTransform(UnaryOperator<Producer<PackedCollection<?>>> transform) {
		this.transform = transform;
	}

	public UnaryOperator<Producer<PackedCollection<?>>> getTransform(int pos) {
		return transforms[pos];
	}

	public void setTransform(int pos, UnaryOperator<Producer<PackedCollection<?>>> transform) {
		this.transforms[pos] = transform;
	}

	protected Producer<PackedCollection<?>> transform(int pos, Producer<PackedCollection<?>> value) {
		if (transforms[pos] != null) value = transforms[pos].apply(value);
		return transform == null ? value : transform.apply(value);
	}
}
