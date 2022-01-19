/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.graph.computations.SummationCellOperation;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class SummationCell extends ScalarCachedStateCell implements Adjustable<Scalar> {
	@Override
	public Supplier<Runnable> push(Producer<Scalar> protein) {
		return new SummationCellOperation(this, protein);
	}

	@Override
	public Supplier<Runnable> updateAdjustment(Producer<Scalar> value) {
		return new OperationList("SummationCell Adjustment Update");
	}
}
