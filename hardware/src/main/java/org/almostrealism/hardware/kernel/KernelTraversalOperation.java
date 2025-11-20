/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.hardware.kernel;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link MemoryDataComputation} that precomputes expression values for each kernel index.
 *
 * <p>Optimization for complex expressions that are expensive to compute within kernel loops.
 * Generates a lookup table of expression results for each index, replacing runtime computation
 * with memory access. Used by {@link KernelTraversalOperationGenerator} to accelerate kernels
 * with repetitive index-dependent calculations.</p>
 *
 * <h2>Optimization Strategy</h2>
 *
 * <pre>{@code
 * // Before optimization:
 * for (i = 0; i < N; i++) {
 *     result[i] = complexExpression(i);  // Computed N times
 * }
 *
 * // After optimization with KernelTraversalOperation:
 * double[] lookupTable = precompute(complexExpression, N);
 * for (i = 0; i < N; i++) {
 *     result[i] = lookupTable[i];  // Just memory access
 * }
 * }</pre>
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Index transformations:</strong> Complex index calculations (e.g., bit-reversal for FFT)</li>
 *   <li><strong>Twiddle factors:</strong> Trigonometric values in signal processing</li>
 *   <li><strong>Coordinate transforms:</strong> Repeated geometric transformations</li>
 *   <li><strong>Polynomial evaluation:</strong> Expensive per-index calculations</li>
 * </ul>
 *
 * <h2>Performance Trade-offs</h2>
 *
 * <ul>
 *   <li><strong>Memory:</strong> Uses {@code N * sizeof(double)} bytes for lookup table</li>
 *   <li><strong>Computation:</strong> Trades runtime computation for memory bandwidth</li>
 *   <li><strong>Speedup:</strong> Most effective when expression has 16+ operations</li>
 *   <li><strong>Limitation:</strong> Only works for fixed-count traversals</li>
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // Automatic generation via KernelTraversalOperationGenerator
 * Expression<?> exp = ...;  // Complex index-dependent expression
 * KernelTraversalOperation<?> op = new KernelTraversalOperation<>();
 * for (int i = 0; i < count; i++) {
 *     op.getExpressions().add(exp.withIndex(index, i).getSimplified());
 * }
 * // Now op can be used as a precomputed lookup table
 * }</pre>
 *
 * @see KernelTraversalOperationGenerator
 * @see KernelSeriesCache
 */
public class KernelTraversalOperation<T extends MemoryData> extends ProducerComputationBase<T, T>
		implements MemoryDataComputation<T>, ExpressionFeatures {
	private List<Expression> expressions;
	private MemoryDataDestinationProducer destination;

	public KernelTraversalOperation() {
		this.expressions = new ArrayList<>();
		this.destination = new MemoryDataDestinationProducer<>(this, i -> new Bytes(expressions.size()));
		setInputs(destination);
		init();
	}

	protected List<Expression> getExpressions() { return expressions; }

	@Override
	public int getMemLength() { return expressions.size(); }

	@Override
	public long getCountLong() { return 1; }

	@Override
	public boolean isFixedCount() { return true; }

	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		Scope<T> scope = super.getScope(context);
		ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

		for (int i = 0; i < getMemLength(); i++) {
			scope.getVariables().add(output.reference(e(i)).assign(expressions.get(i)));
		}

		return scope;
	}

	@Override
	public Evaluable<T> get() {
		ComputeContext<MemoryData> ctx = Hardware.getLocalHardware().getComputer().getContext(this);
		AcceleratedComputationEvaluable<T> ev = new AcceleratedComputationEvaluable<>(ctx, this);
		ev.setDestinationFactory(destination.getDestinationFactory());
		ev.compile();
		return ev;
	}
}
