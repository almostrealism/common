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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Defaults;
import io.almostrealism.relation.DynamicProducer;

// TODO  Rename
public class LongScaleFactor implements ScaleFactor<Long> {
	private double scale;

	public LongScaleFactor() { }

	public LongScaleFactor(double scale) { this.scale = scale; }

	@Override
	public Producer<Long> getResultant(Producer<Long> value) {
		return new DynamicProducer<>(args -> (long) (value.get().evaluate(args) * scale));
	}

	@Override
	public void setScale(double s) { this.scale = s; }

	@Override
	public double getScale() { return scale; }

	@Override
	public String toString() { return Defaults.displayFormat.format(scale); }
}
