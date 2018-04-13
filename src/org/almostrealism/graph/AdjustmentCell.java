/*
 * Copyright 2018 Michael Murray
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

import org.almostrealism.graph.Cell;

/**
 * TODO  Why does this extend CachedStateCell? It does not use the cached value.
 */
public class AdjustmentCell<T, R> extends CachedStateCell<R> {
	private Cell<T> cell;
	private CellAdjustment<T, R> adjust;
	
	public AdjustmentCell(Cell<T> cell, CellAdjustment<T, R> adjustment) {
		this.cell = cell;
		this.adjust = adjustment;
	}
	
	public void push(long i) {
		adjust.adjust(cell, getProtein(i));
//		super.push(i);
	}
}
