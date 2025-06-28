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

package org.almostrealism.hardware.instructions;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.Computation;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.Scope;
import io.almostrealism.uml.Named;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.kernel.KernelSeriesCache;
import org.almostrealism.hardware.kernel.KernelTraversalOperation;
import org.almostrealism.hardware.kernel.KernelTraversalOperationGenerator;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.Describable;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ComputationScopeCompiler<T> implements KernelStructureContext,
		ScopeLifecycle, Destroyable, OperationInfo, Signature, ConsoleFeatures {
	public static boolean verboseCompile = SystemUtils.isEnabled("AR_HARDWARE_VERBOSE_COMPILE").orElse(false);

	public static ScopeTimingListener timing;

	private Computation<T> computation;
	private NameProvider nameProvider;

	private KernelSeriesCache kernelSeriesCache;
	private KernelTraversalOperationGenerator traversalGenerator;
	private OptionalLong kernelMaximum;

	private Scope<T> scope;

	public ComputationScopeCompiler(Computation<T> computation, NameProvider nameProvider) {
		this.computation = computation;
		this.nameProvider = nameProvider;
	}

	public boolean isKernelStructureSupported() {
		if (computation instanceof KernelTraversalOperation) {
			// Kernel traversal caching should not be recursively
			// applied to the operation that generates traversal
			// series data for another operation
			return false;
		}

		return true;
	}

	public Computation<T> getComputation() { return computation; }

	@Override
	public OperationMetadata getMetadata() {
		return computation instanceof OperationInfo ? ((OperationInfo) computation).getMetadata() : null;
	}

	@Override
	public List<ComputeRequirement> getComputeRequirements() {
		if (scope != null) return scope.getComputeRequirements();
		if (getComputation() instanceof OperationInfo) {
			return ((OperationInfo) getComputation()).getComputeRequirements();
		}

		return OperationInfo.super.getComputeRequirements();
	}

	@Override
	public OptionalLong getKernelMaximum() {
		if (kernelMaximum == null) {
			kernelMaximum = Countable.isFixedCount(getComputation()) ?
					OptionalLong.of(Countable.countLong(getComputation())) : OptionalLong.empty();
		}

		return kernelMaximum;
	}

	@Override
	public KernelSeriesProvider getSeriesProvider() {
		return isKernelStructureSupported() ? kernelSeriesCache : null;
	}

	@Override
	public KernelTraversalProvider getTraversalProvider() {
		return isKernelStructureSupported() ? traversalGenerator : null;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.super.prepareArguments(map);
		getComputation().prepareArguments(map);
	}

	public void prepareScope(ScopeInputManager manager) {
		prepareScope(manager, this);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		ScopeLifecycle.super.prepareScope(manager, context);
		getComputation().prepareScope(manager, context);

		this.kernelSeriesCache = KernelSeriesCache.create(getComputation(),
				data -> manager.argumentForInput(nameProvider).apply(() -> new Provider<>(data)));
		this.traversalGenerator = KernelTraversalOperationGenerator.create(getComputation(),
				data -> manager.argumentForInput(nameProvider).apply((Supplier<Evaluable<?>>) data));
	}

	@Override
	public void resetArguments() {
		ScopeLifecycle.super.resetArguments();
		getComputation().resetArguments();
		this.kernelMaximum = null;
	}

	public Scope<T> getScope() { return scope; }

	public synchronized Scope<T> compile() {
		if (scope != null) {
			warn("Attempting to compile an operation which was already compiled");
			return scope;
		}

		if (verboseCompile) log("Compiling " + Named.nameOf(getComputation()));

		try {
			Computation<T> c = getComputation();

			long start = System.nanoTime();

			scope = c.getScope(this);
			if (timing != null) {
				timing.recordDuration(getMetadata(), scope.getMetadata(),
						"getScope", System.nanoTime() - start);
			}

			start = System.nanoTime();
			scope.convertArgumentsToRequiredScopes(this);
			if (timing != null) {
				timing.recordDuration(getMetadata(), scope.getMetadata(),
						"convertRequired", System.nanoTime() - start);
			}

			scope = scope.simplify(this);

			if (verboseCompile) log("Done compiling " + Named.nameOf(getComputation()));
			return scope;
		} catch (Exception e) {
			throw new HardwareException("Cannot compile " + Named.nameOf(getComputation()), e);
		}
	}

	public synchronized void postCompile() {
		if (getComputation() instanceof Shape) {
			TraversalPolicy shape = scope.getMetadata().getShape();

			if (shape == null) {
				warn("Missing TraversalPolicy for Scope metadata");
				scope.setMetadata(scope.getMetadata().withShape(((Shape<?>) getComputation()).getShape()));
			} else if (!shape.equals(((Shape<?>) getComputation()).getShape())) {
				throw new IllegalArgumentException("Shape mismatch between Scope metadata and Computation");
			}
		}

		scope.setMetadata(scope.getMetadata().withSignature(signature()));
	}

	public boolean isCompiled() { return scope != null; }

	@Override
	public String signature() {
		String signature = getMetadata().getSignature();
		if (signature == null) return null;

		if (computation instanceof Process<?,?>) {
			// TODO  This may not be enough information to distinguish between
			// TODO  operations, as a Process that had arguments (A, A, B) and
			// TODO  (A, B, B) would retain the same signature
			int distinct = ((Process<?,?>) computation).children()
					.collect(Collectors.toSet()).size();
			return signature + "&distinct=" + distinct + ";";
		}

		return signature;
	}

	@Override
	public void destroy() {
		scope = null;

		if (kernelSeriesCache != null) {
			kernelSeriesCache.destroy();
			kernelSeriesCache = null;
		}

		if (traversalGenerator != null) {
			traversalGenerator.destroy();
			traversalGenerator = null;
		}
	}

	@Override
	public String describe() {
		if (getComputation() instanceof Describable) {
			return ((Describable) getComputation()).describe();
		} else {
			return toString();
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
