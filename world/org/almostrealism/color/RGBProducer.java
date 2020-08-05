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

package org.almostrealism.color;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

/**
 * {@link RGBProducer} is implemented by any class that can produce an {@link RGB} object
 * given some array of input objects.
 *
 * @author Michael Murray
 */
public interface RGBProducer extends Producer<RGB> {
	static Producer<RGB> fromScalar(Producer<Scalar> value) {
		return new DynamicProducer<>(args -> {
			double v = value.evaluate(args).getValue();
			return new RGB(v, v, v);
		});
	}

	static RGBProducer fromScalar(Scalar value) {
		return StaticProducer.of(new RGB(value.getValue(), value.getValue(), value.getValue()));
	}

	static RGBProducer fromScalar(double value) {
		return StaticProducer.of(new RGB(value, value, value));
	}
}
