/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.relation.Producer;

import java.util.function.Supplier;

public class AdjustmentCell<A, R> extends CellAdapter<R> implements Adjustable<R> {
	private final Adjustable<A> cell;
	private final Adjustment<A> adjust;
	
	public AdjustmentCell(Adjustable<A> cell, Adjustment<A> adjustment) {
		this.cell = cell;
		this.adjust = adjustment;
	}

	public Adjustable<A> getAdjustable() { return cell; }

	public Adjustment<A> getAdjustment() { return adjust; }

	@Override
	public Supplier<Runnable> setup() { return adjust.setup(); }

	@Override
	public Supplier<Runnable> updateAdjustment(Producer<R> value) {
		if (adjust instanceof Adjustable) {
			return ((Adjustable) adjust).updateAdjustment(value);
		} else {
			System.out.println("WARN: " + adjust.getClass().getSimpleName() + " is not Adjustable, " +
								"but the AdjustmentCell it is used by should be adjusted by " +
								value.getClass().getSimpleName());
			return () -> () -> { };
		}
	}

	@Override
	public Supplier<Runnable> push(Producer<R> protein) {
		return adjust.adjust(cell);
	}
}
