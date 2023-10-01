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

package org.almostrealism.heredity;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Defaults;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.Optional;

public class ScaleFactor implements Factor<PackedCollection<?>>, ScalarFeatures, CollectionFeatures {
	private Scalar scale;

	public ScaleFactor() { scale = new Scalar(0.0); }

	public ScaleFactor(double scale) { this.scale = new Scalar(scale); }

	public ScaleFactor(Scalar scale) { this.scale = scale; }

	@Override
	public Producer<PackedCollection<?>> getResultant(Producer<PackedCollection<?>> value) {
		return multiply(value, (Producer) v(scale), args -> {
			PackedCollection<?> result = new PackedCollection<>(1);

//			if (value instanceof StaticCollectionComputation) {
//				result.setMem(((StaticCollectionComputation) value).getValue().toDouble(0) * scale.toDouble(0));
//			} else {
				result.setMem(value.get().evaluate(args).toDouble(0) * scale.toDouble(0));
//			}

			return result;
		});
	}

	public void setScaleValue(double s) { this.scale = new Scalar(s); }

	public double getScaleValue() { return Optional.ofNullable(this.scale).map(Scalar::getValue).orElse(0.0); }

	public Scalar getScale() { return scale; }

	@Override
	public String signature() {
		return Double.toHexString(scale.getValue());
	}

	@Override
	public String toString() { return Defaults.displayFormat.format(scale.getValue()); }
}
