/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.model;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.DefaultGradientPropagation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class BranchBlock implements Block {
	private TraversalPolicy shape;

	private Cell<PackedCollection<?>> entry;
	private Receptor<PackedCollection<?>> push;
	private Receptor<PackedCollection<?>> downstream;

	private Cell<PackedCollection<?>> backwards;
	private List<CellularPropagation<PackedCollection<?>>> children;
	private PackedCollection<?> gradient;
	private Receptor<PackedCollection<?>> aggregator;

	public BranchBlock(TraversalPolicy shape) {
		this.shape = shape;

		this.push = in -> {
			OperationList op = new OperationList();
			children.stream().map(CellularPropagation::getForward).forEach(r -> op.add(r.push(in)));
			if (downstream != null) op.add(downstream.push(in));
			return op;
		};

		this.children = new ArrayList<>();
		this.gradient = new PackedCollection<>(shape);

		if (DefaultGradientPropagation.enableDiagnosticGrad) {
			this.aggregator = (input) -> {
				OperationList op = new OperationList("BranchBlock Aggregate");
				op.add(a("aggregate",
						p(gradient.each()), add(p(gradient.each()), input)));
				op.add(() -> () -> {
					gradient.print();
				});
				return op;
			};
		} else {
			this.aggregator = (input) ->
					a("aggregate",
							p(gradient.each()), add(p(gradient.each()), input));
		}
	}

	@Override
	public Supplier<Runnable> setup() {
		return new OperationList("BranchBlock Setup");
	}

	@Override
	public TraversalPolicy getInputShape() {
		return shape;
	}

	@Override
	public TraversalPolicy getOutputShape() {
		return shape;
	}

	public List<CellularPropagation<PackedCollection<?>>> getChildren() {
		return Collections.unmodifiableList(children);
	}

	@Override
	public Cell<PackedCollection<?>> getForward() {
		if (entry == null) {
			entry = new Cell<>() {
				@Override
				public Supplier<Runnable> push(Producer<PackedCollection<?>> in) {
					return push.push(in);
				}

				@Override
				public void setReceptor(Receptor<PackedCollection<?>> r) {
					if (BranchBlock.this.downstream != null) {
						warn("Replacing receptor");
					}

					BranchBlock.this.downstream = r;
				}
			};
		}

		return entry;
	}

	@Override
	public Cell<PackedCollection<?>> getBackward() {
		if (backwards == null) {
			backwards = Cell.of((input, next) -> {
				OperationList op = new OperationList("BranchBlock Backward");
				op.add(aggregator.push(input));
				op.add(next.push(p(gradient)));
				op.add(a("clearBranchGradient", p(gradient.each()), c(0.0)));
				return op;
			});
		}

		return backwards;
	}

	public <T extends CellularPropagation<PackedCollection<?>>> T append(T l) {
		children.add(l);
		l.getBackward().setReceptor(aggregator);
		return l;
	}
}
