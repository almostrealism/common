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

package org.almostrealism.hardware;

import io.almostrealism.profile.OperationMetadata;

/**
 * Observes how operations obtain, bind and dispatch compiled kernels: whether an operation
 * compiled its own kernel or reused one from the instruction cache, how many arguments the kernel
 * takes and how many inputs were folded into its aggregate argument, and the aggregate copies
 * issued around each dispatch.
 *
 * <p>A listener is installed through {@link AcceleratedOperation#bindingListener}, alongside the
 * profiling listeners ({@link HardwareOperator#timingListener},
 * {@link org.almostrealism.hardware.ctx.AbstractComputeContext#compilationTimingListener}). It
 * observes only: every notification is made after the decision it reports has been taken, and
 * nothing a listener does or returns can change which kernel is used, how arguments are bound or
 * which copies are made. With no listener installed nothing is reported and nothing is computed
 * to report.</p>
 *
 * <p>Notifications are made on whichever thread compiles, binds or dispatches, so an
 * implementation that accumulates them must be thread-safe.</p>
 *
 * @see InstructionCacheCollisionException
 */
public interface KernelBindingListener {
	/**
	 * An operation compiled its own kernel: the instruction cache held no kernel for its signature
	 * and compute context, or the operation has no signature and so cannot share one. It is
	 * reported once the operation's scope is compiled and its arguments are bound, before the
	 * compute context translates the scope into native code, so a failure of that translation
	 * (such as a kernel over a device's argument limit) follows the notification.
	 *
	 * @param metadata        the metadata of the compiling operation
	 * @param signature       the signature the kernel is cached under, or null when it is not shared
	 * @param kernelArguments the number of arguments the compiled kernel takes
	 * @param foldedArguments the number of inputs folded into the kernel's aggregate argument
	 * @param aggregateLength the number of elements in the aggregate argument, 0 when nothing is folded
	 */
	default void compiled(OperationMetadata metadata, String signature,
						  int kernelArguments, int foldedArguments, int aggregateLength) { }

	/**
	 * An operation is about to bind a kernel another operation compiled under the same signature and
	 * compute context. This is a hit in the instruction cache; the binding that follows may still
	 * fail with an {@link InstructionCacheCollisionException}.
	 *
	 * @param metadata        the metadata of the reusing operation
	 * @param signature       the signature the kernel is cached under
	 * @param kernelArguments the number of arguments the compiled kernel takes
	 */
	default void reused(OperationMetadata metadata, String signature, int kernelArguments) { }

	/**
	 * An operation dispatched its kernel once.
	 *
	 * @param metadata    the metadata of the dispatching operation
	 * @param aggregating whether the operation folds inputs into an aggregate argument, so that
	 *                    aggregate copies may surround the dispatch
	 */
	default void dispatched(OperationMetadata metadata, boolean aggregating) { }

	/**
	 * One aggregated input's slice was copied between the input and the aggregate argument. Copies
	 * that a dispatch leaves out, such as the copy-in of a read-only slice already copied by an
	 * earlier dispatch, are not reported.
	 *
	 * @param copyBack true for a copy from the aggregate back to the input after the kernel, false
	 *                 for a copy into the aggregate before it
	 * @param length   the number of elements copied
	 */
	default void aggregateCopied(boolean copyBack, int length) { }
}
