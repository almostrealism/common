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

package org.almostrealism.color.computations;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.color.RGB;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.util.StaticEvaluable;

/**
 * {@link RGBEvaluable} is implemented by any class that can produce an {@link RGB} object
 * given some array of input objects.
 *
 * @author Michael Murray
 */
public interface RGBEvaluable extends Evaluable<RGB>, RGBFeatures {
	default RGBEvaluable add(Evaluable<RGB> operand) {
		return cadd(this, operand);
	}

	default RGBEvaluable subtract(Evaluable<RGB> operand) { return csubtract(this, operand); }

	default RGBEvaluable multiply(Evaluable<RGB> operand) {
		return cmultiply(this, operand);
	}

	default RGBEvaluable scalarMultiply(Evaluable<Scalar> operand) { return cscalarMultiply(this, operand); }

	default RGBEvaluable scalarMultiply(Scalar operand) {
		return scalarMultiply(StaticEvaluable.of(operand).get());
	}

	default RGBEvaluable scalarMultiply(double operand) {
		return scalarMultiply(new Scalar(operand));
	}
	
	default RGBEvaluable minus() {
		return cminus(this);
	}
}
