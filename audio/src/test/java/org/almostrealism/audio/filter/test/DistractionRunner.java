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

package org.almostrealism.audio.filter.test;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Utility for executing random "distraction" computations to force GPU/CPU pipeline state changes.
 * <p>
 * This is useful for performance testing to simulate realistic workloads where different compute
 * kernels are executed in an interleaved pattern, preventing pipeline state caching.
 * </p>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * DistractionRunner runner = new DistractionRunner(50000, 0.1);
 * runner.addMultiply();
 * runner.addAdd();
 * runner.addSum();
 * runner.addExp();
 *
 * // During testing loop
 * runner.maybeExecute();  // Randomly executes one distractor based on probability
 *
 * // Cleanup
 * runner.destroy();
 * </pre>
 */
public class DistractionRunner implements Destroyable, CollectionFeatures {
	private final int bufferSize;
	private final double probability;
	private final Random random;

	private final List<DistractionOperation> operations;
	private PackedCollection bufferA;
	private PackedCollection bufferB;
	private PackedCollection resultBuffer;
	private PackedCollection scalarResult;

	private long executionCount = 0;

	/**
	 * Creates a new distraction runner.
	 *
	 * @param bufferSize   Size of buffers for operations (in elements)
	 * @param probability  Probability of executing a distraction (0.0 to 1.0)
	 */
	public DistractionRunner(int bufferSize, double probability) {
		this(bufferSize, probability, new Random(999));
	}

	/**
	 * Creates a new distraction runner with a specific random seed.
	 *
	 * @param bufferSize   Size of buffers for operations (in elements)
	 * @param probability  Probability of executing a distraction (0.0 to 1.0)
	 * @param random       Random number generator for selection
	 */
	public DistractionRunner(int bufferSize, double probability, Random random) {
		this.bufferSize = bufferSize;
		this.probability = probability;
		this.random = random;
		this.operations = new ArrayList<>();
	}

	/**
	 * Initializes the buffers with random data.
	 * Must be called before adding operations.
	 */
	public void initialize() {
		if (bufferA != null) return;  // Already initialized

		bufferA = new PackedCollection(bufferSize).randnFill();
		bufferB = new PackedCollection(bufferSize).randnFill();
		resultBuffer = new PackedCollection(bufferSize);
		scalarResult = new PackedCollection(1);
	}

	/**
	 * Adds a custom operation with its result buffer.
	 *
	 * @param name       Name of the operation (for logging)
	 * @param operation  The compiled operation to execute
	 * @param result     The result buffer for this operation
	 */
	public void addOperation(String name, Evaluable<PackedCollection> operation, PackedCollection result) {
		operations.add(new DistractionOperation(name, operation, result));
	}

	/**
	 * Adds a custom producer as a distraction.
	 *
	 * @param name      Name of the operation
	 * @param producer  Producer to compile and add
	 */
	public void addProducer(String name, Producer<PackedCollection> producer) {
		initialize();
		Evaluable<PackedCollection> op = producer.get();
		addOperation(name, op, resultBuffer);
	}

	/**
	 * Adds element-wise multiplication: A * B
	 */
	public void addMultiply() {
		initialize();
		addProducer("multiply", multiply(p(bufferA), p(bufferB)));
	}

	/**
	 * Adds element-wise addition: A + B
	 */
	public void addAdd() {
		initialize();
		addProducer("add", add(p(bufferA), p(bufferB)));
	}

	/**
	 * Adds element-wise subtraction: A - B
	 */
	public void addSubtract() {
		initialize();
		addProducer("subtract", subtract(p(bufferA), p(bufferB)));
	}

	/**
	 * Adds element-wise division: A / B
	 */
	public void addDivide() {
		initialize();
		addProducer("divide", divide(p(bufferA), p(bufferB)));
	}

	/**
	 * Adds sum reduction: sum(A)
	 */
	public void addSum() {
		initialize();
		Evaluable<PackedCollection> op = sum(p(bufferA)).get();
		addOperation("sum", op, scalarResult);
	}

	/**
	 * Adds element-wise exponential: exp(A)
	 */
	public void addExp() {
		initialize();
		addProducer("exp", exp(p(bufferA)));
	}

	/**
	 * Adds element-wise power: pow(A, 2)
	 */
	public void addPow() {
		initialize();
		addProducer("pow", pow(p(bufferA), c(2.0)));
	}

	/**
	 * Adds element-wise square root: sqrt(abs(A))
	 */
	public void addSqrt() {
		initialize();
		addProducer("sqrt", pow(abs(p(bufferA)), c(0.5)));
	}

	/**
	 * Adds element-wise absolute value: abs(A)
	 */
	public void addAbs() {
		initialize();
		addProducer("abs", abs(p(bufferA)));
	}

	/**
	 * Adds element-wise minimum: min(A, B)
	 */
	public void addMin() {
		initialize();
		addProducer("min", min(p(bufferA), p(bufferB)));
	}

	/**
	 * Adds element-wise maximum: max(A, B)
	 */
	public void addMax() {
		initialize();
		addProducer("max", max(p(bufferA), p(bufferB)));
	}

	/**
	 * Adds zeros initialization
	 */
	public void addZeros() {
		initialize();
		addProducer("zeros", multiply(p(bufferA), c(0.0)));
	}

	/**
	 * Randomly executes one distraction operation based on the configured probability.
	 *
	 * @return {@code true} if a distraction was executed
	 */
	public boolean maybeExecute() {
		if (operations.isEmpty() || random.nextDouble() >= probability) {
			return false;
		}

		int index = random.nextInt(operations.size());
		DistractionOperation op = operations.get(index);
		op.execute();
		executionCount++;
		return true;
	}

	/**
	 * Executes N random distraction operations to flood the pipeline.
	 * <p>
	 * This method is useful when you want to execute multiple distractions
	 * in a row to ensure the target operation's pipeline state is not cached.
	 * </p>
	 *
	 * @param count  Number of distraction operations to execute
	 */
	public void executeMultiple(int count) {
		if (operations.isEmpty()) return;

		for (int i = 0; i < count; i++) {
			int index = random.nextInt(operations.size());
			DistractionOperation op = operations.get(index);
			op.execute();
			executionCount++;
		}
	}

	/**
	 * Calculates how many distraction operations to execute based on probability.
	 * <p>
	 * With probability P, this returns N such that the target operation represents
	 * (1-P) of the total operations. For example, with P=0.9, this returns 9,
	 * meaning 9 distractions should be executed per 1 target operation.
	 * </p>
	 *
	 * @return Number of distraction operations to execute per target operation
	 */
	public int getDistractionsPerOperation() {
		if (probability <= 0.0 || probability >= 1.0) {
			return 0;
		}
		return (int) Math.round(probability / (1.0 - probability));
	}

	/**
	 * Returns the total number of distractions executed.
	 */
	public long getExecutionCount() {
		return executionCount;
	}

	/**
	 * Returns the number of different operation types registered.
	 */
	public int getOperationCount() {
		return operations.size();
	}

	/**
	 * Resets the execution counter.
	 */
	public void resetCount() {
		executionCount = 0;
	}

	@Override
	public void destroy() {
		if (bufferA != null) bufferA.destroy();
		if (bufferB != null) bufferB.destroy();
		if (resultBuffer != null) resultBuffer.destroy();
		if (scalarResult != null) scalarResult.destroy();
		operations.clear();
	}

	/**
	 * Internal class representing a single distraction operation.
	 */
	private static class DistractionOperation {
		private final String name;
		private final Evaluable<PackedCollection> operation;
		private final PackedCollection result;

		public DistractionOperation(String name, Evaluable<PackedCollection> operation, PackedCollection result) {
			this.name = name;
			this.operation = operation;
			this.result = result;
		}

		public void execute() {
			operation.into(result).evaluate();
		}

		public String getName() {
			return name;
		}
	}
}
