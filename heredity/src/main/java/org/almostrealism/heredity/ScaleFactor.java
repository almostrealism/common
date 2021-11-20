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

package org.almostrealism.heredity;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Defaults;

import java.util.Base64;
import java.util.Optional;

public class ScaleFactor implements Factor<Scalar>, ScalarFeatures {
	private Scalar scale;
	
	public ScaleFactor() { scale = new Scalar(0.0); }
	
	public ScaleFactor(double scale) { this.scale = new Scalar(scale); }

	public ScaleFactor(Scalar scale) { this.scale = scale; }

	@Override
	public Producer<Scalar> getResultant(Producer<Scalar> value) {
		return scalarsMultiply(value, v(scale));
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
