/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.benchmark;

import io.almostrealism.profile.CompilationTimingListener;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.KernelBindingListener;
import org.almostrealism.hardware.ctx.AbstractComputeContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the kernel binding events of one benchmark instance at a time.
 *
 * <p>While {@link #install() installed} it receives every {@link KernelBindingListener}
 * notification and every native compilation time
 * ({@link AbstractComputeContext#compilationTimingListener}), and accumulates them until
 * {@link #drain()} hands them over as one {@link Events}. The benchmark drains it once before
 * an instance runs and once after, so each instance is credited with exactly the work it caused.
 * Both hooks only observe, so installing the recorder does not change what is compiled, reused,
 * bound or copied.</p>
 */
public class KernelBindingRecorder implements KernelBindingListener, CompilationTimingListener {
	/** One kernel an operation obtained: compiled for it, or reused from the cache. */
	public static final class Kernel {
		/** Whether the operation compiled the kernel rather than reusing it. */
		private final boolean compiled;
		/** The signature the kernel is cached under, or null when it is not shared. */
		private final String signature;
		/** The number of arguments the kernel takes. */
		private final int arguments;
		/** The number of inputs folded into its aggregate, or -1 when not known (a reuse). */
		private final int folded;
		/** The aggregate length in elements, or -1 when not known (a reuse). */
		private final int aggregateLength;

		/**
		 * Creates a record of an obtained kernel.
		 *
		 * @param compiled        whether the operation compiled the kernel
		 * @param signature       the signature the kernel is cached under, or null
		 * @param arguments       the number of arguments the kernel takes
		 * @param folded          the number of folded inputs, or -1 when not known
		 * @param aggregateLength the aggregate length, or -1 when not known
		 */
		Kernel(boolean compiled, String signature, int arguments, int folded, int aggregateLength) {
			this.compiled = compiled;
			this.signature = signature;
			this.arguments = arguments;
			this.folded = folded;
			this.aggregateLength = aggregateLength;
		}

		/**
		 * Returns whether the operation compiled the kernel rather than reusing it.
		 *
		 * @return true for a compilation (a cache miss)
		 */
		public boolean isCompiled() { return compiled; }

		/**
		 * Returns the signature the kernel is cached under.
		 *
		 * @return the signature, or null when the kernel is not shared
		 */
		public String getSignature() { return signature; }

		/**
		 * Returns the number of arguments the kernel takes.
		 *
		 * @return the argument count
		 */
		public int getArguments() { return arguments; }

		/**
		 * Returns the number of inputs folded into the kernel's aggregate.
		 *
		 * @return the folded count, or -1 for a reused kernel
		 */
		public int getFolded() { return folded; }

		/**
		 * Returns the length of the kernel's aggregate argument.
		 *
		 * @return the aggregate length in elements, or -1 for a reused kernel
		 */
		public int getAggregateLength() { return aggregateLength; }
	}

	/** Everything recorded between two {@link #drain()} calls. */
	public static final class Events {
		/** The kernels obtained, in order. */
		private final List<Kernel> kernels;
		/** The number of dispatches. */
		private final long dispatches;
		/** The number of dispatches of operations with an aggregate argument. */
		private final long aggregatingDispatches;
		/** The number of aggregate slices copied in. */
		private final long copiesIn;
		/** The number of aggregate slices copied back. */
		private final long copiesBack;
		/** The number of native compilations. */
		private final long nativeCompilations;
		/** The total native compilation time in nanoseconds. */
		private final long nativeCompileNanos;

		/**
		 * Creates a set of events.
		 *
		 * @param kernels               the kernels obtained
		 * @param dispatches            the number of dispatches
		 * @param aggregatingDispatches the number of dispatches with an aggregate argument
		 * @param copiesIn              the number of aggregate slices copied in
		 * @param copiesBack            the number of aggregate slices copied back
		 * @param nativeCompilations    the number of native compilations
		 * @param nativeCompileNanos    the total native compilation time
		 */
		Events(List<Kernel> kernels, long dispatches, long aggregatingDispatches, long copiesIn,
			   long copiesBack, long nativeCompilations, long nativeCompileNanos) {
			this.kernels = kernels;
			this.dispatches = dispatches;
			this.aggregatingDispatches = aggregatingDispatches;
			this.copiesIn = copiesIn;
			this.copiesBack = copiesBack;
			this.nativeCompilations = nativeCompilations;
			this.nativeCompileNanos = nativeCompileNanos;
		}

		/** Returns the kernels obtained, in order. */
		public List<Kernel> getKernels() { return kernels; }

		/** Returns the number of dispatches. */
		public long getDispatches() { return dispatches; }

		/** Returns the number of dispatches of operations with an aggregate argument. */
		public long getAggregatingDispatches() { return aggregatingDispatches; }

		/** Returns the number of aggregate slices copied in. */
		public long getCopiesIn() { return copiesIn; }

		/** Returns the number of aggregate slices copied back. */
		public long getCopiesBack() { return copiesBack; }

		/** Returns the number of native compilations. */
		public long getNativeCompilations() { return nativeCompilations; }

		/** Returns the total native compilation time in nanoseconds. */
		public long getNativeCompileNanos() { return nativeCompileNanos; }
	}

	/** The kernels obtained since the last drain. */
	private List<Kernel> kernels = new ArrayList<>();
	/** The dispatches since the last drain. */
	private long dispatches;
	/** The aggregating dispatches since the last drain. */
	private long aggregatingDispatches;
	/** The slices copied in since the last drain. */
	private long copiesIn;
	/** The slices copied back since the last drain. */
	private long copiesBack;
	/** The native compilations since the last drain. */
	private long nativeCompilations;
	/** The native compilation time since the last drain. */
	private long nativeCompileNanos;

	/** The binding listener that was installed before this recorder, restored by {@link #uninstall()}. */
	private KernelBindingListener previousBinding;
	/** The compilation listener that was installed before this recorder, restored by {@link #uninstall()}. */
	private CompilationTimingListener previousCompilation;

	/** Installs this recorder as the binding listener and the compilation timing listener. */
	public void install() {
		previousBinding = AcceleratedOperation.bindingListener;
		previousCompilation = AbstractComputeContext.compilationTimingListener;
		AcceleratedOperation.bindingListener = this;
		AbstractComputeContext.compilationTimingListener = this;
	}

	/** Restores the listeners that were installed before {@link #install()}. */
	public void uninstall() {
		AcceleratedOperation.bindingListener = previousBinding;
		AbstractComputeContext.compilationTimingListener = previousCompilation;
	}

	/**
	 * Returns everything recorded since the previous drain and starts recording afresh.
	 *
	 * @return the recorded events
	 */
	public synchronized Events drain() {
		Events events = new Events(kernels, dispatches, aggregatingDispatches, copiesIn, copiesBack,
				nativeCompilations, nativeCompileNanos);
		kernels = new ArrayList<>();
		dispatches = 0;
		aggregatingDispatches = 0;
		copiesIn = 0;
		copiesBack = 0;
		nativeCompilations = 0;
		nativeCompileNanos = 0;
		return events;
	}

	@Override
	public synchronized void compiled(OperationMetadata metadata, String signature,
									  int kernelArguments, int foldedArguments, int aggregateLength) {
		kernels.add(new Kernel(true, signature, kernelArguments, foldedArguments, aggregateLength));
	}

	@Override
	public synchronized void reused(OperationMetadata metadata, String signature, int kernelArguments) {
		kernels.add(new Kernel(false, signature, kernelArguments, -1, -1));
	}

	@Override
	public synchronized void dispatched(OperationMetadata metadata, boolean aggregating) {
		dispatches++;
		if (aggregating) aggregatingDispatches++;
	}

	@Override
	public synchronized void aggregateCopied(boolean copyBack, int length) {
		if (copyBack) {
			copiesBack++;
		} else {
			copiesIn++;
		}
	}

	@Override
	public synchronized void recordCompilation(OperationMetadata metadata, List<ArrayVariable<?>> arguments,
											   String code, long nanos) {
		nativeCompilations++;
		nativeCompileNanos += nanos;
	}
}
