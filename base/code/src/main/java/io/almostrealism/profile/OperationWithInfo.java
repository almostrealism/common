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

package io.almostrealism.profile;

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Operation;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * A decorator that pairs an {@link Operation} with explicit {@link OperationMetadata},
 * enabling the operation to participate in the profiling system.
 *
 * <p>Many operations do not natively implement {@link OperationInfo}. This wrapper
 * attaches metadata to such operations so that the profiling listeners (e.g.,
 * {@link OperationTimingListener}) can identify them when recording durations.
 * The wrapper delegates all {@link Operation} methods to the underlying operation
 * while providing the metadata via {@link #getMetadata()}.</p>
 *
 * <p>When {@link #get()} is called, it returns a {@link RunnableWithInfo} that
 * similarly wraps the runnable produced by the underlying operation, ensuring
 * that metadata is preserved through the get-then-run lifecycle.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * OperationMetadata meta = new OperationMetadata("MyOp", "Custom operation");
 * OperationWithInfo wrapped = OperationWithInfo.of(meta, existingOperation);
 * listener.recordDuration(wrapped.get());  // metadata is available for profiling
 * }</pre>
 *
 * @see OperationInfo
 * @see OperationMetadata
 * @see RunnableWithInfo
 *
 * @author Michael Murray
 */
public class OperationWithInfo implements Operation, OperationInfo {
	/** The metadata describing this operation. */
	private final OperationMetadata metadata;

	/** The underlying operation being wrapped. */
	private final Operation operation;

	/**
	 * Creates a wrapper pairing the given metadata with the given operation.
	 *
	 * @param metadata  the metadata to associate with this operation
	 * @param operation the underlying operation to delegate to
	 */
	public OperationWithInfo(OperationMetadata metadata, Operation operation) {
		this.metadata = metadata;
		this.operation = operation;
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public Collection<Process<?, ?>> getChildren() { return operation.getChildren(); }

	@Override
	public Runnable get() { return new RunnableWithInfo(getMetadata(), operation.get()); }

	@Override
	public long getOutputSize() {
		return operation.getOutputSize();
	}

	@Override
	public Process<Process<?, ?>, Runnable> isolate() {
		return this;
	}

	@Override
	public Process<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return this;
	}

	@Override
	public String describe() {
		return getMetadata().getShortDescription();
	}

	/**
	 * Creates a wrapper pairing the given metadata with the given operation.
	 *
	 * @param metadata  the metadata to associate
	 * @param operation the underlying operation
	 * @return a new {@code OperationWithInfo}
	 */
	public static OperationWithInfo of(OperationMetadata metadata, Operation operation) {
		return new OperationWithInfo(metadata, operation);
	}

	/**
	 * Creates a wrapper from a metadata and a runnable supplier, converting
	 * the supplier to an {@link Operation} first.
	 *
	 * @param metadata the metadata to associate
	 * @param op       a supplier that produces a runnable
	 * @return a new {@code OperationWithInfo}
	 */
	public static OperationWithInfo of(OperationMetadata metadata, Supplier<Runnable> op) {
		return OperationWithInfo.of(metadata, Operation.of(op));
	}

	/**
	 * A {@link Runnable} decorator that carries {@link OperationMetadata},
	 * enabling profiling listeners to identify the operation when timing
	 * its execution.
	 */
	public static class RunnableWithInfo implements OperationInfo, Runnable {
		/** The metadata associated with this runnable. */
		private final OperationMetadata metadata;

		/** The underlying runnable being wrapped. */
		private final Runnable runnable;

		/**
		 * Creates a runnable wrapper with the given metadata.
		 *
		 * @param metadata the metadata to associate
		 * @param runnable the underlying runnable to delegate to
		 */
		public RunnableWithInfo(OperationMetadata metadata, Runnable runnable) {
			this.metadata = metadata;
			this.runnable = runnable;
		}

		@Override
		public OperationMetadata getMetadata() { return metadata; }

		@Override
		public void run() { runnable.run(); }

		/** {@inheritDoc} Returns the short description from the associated metadata. */
		@Override
		public String describe() {
			return getMetadata().getShortDescription();
		}

		/**
		 * Creates a {@link RunnableWithInfo} pairing the given metadata with the given runnable.
		 *
		 * @param metadata the metadata to associate
		 * @param runnable the underlying runnable
		 * @return a new wrapper
		 */
		public static RunnableWithInfo of(OperationMetadata metadata, Runnable runnable) {
			return new RunnableWithInfo(metadata, runnable);
		}
	}
}
