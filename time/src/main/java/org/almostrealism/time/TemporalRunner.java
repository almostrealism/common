/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.time;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.Computation;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.OperationComputation;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Compactable;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TemporalRunner implements Setup, Temporal, OperationComputation<Void>, HardwareFeatures {
	private Supplier<Runnable> setup, run;
	private Runnable s, r;

	public TemporalRunner(Temporal o, int iter) {
		this(((Setup) o).setup(), o.tick(), iter);
	}

	public TemporalRunner(Supplier<Runnable> setup, Supplier<Runnable> tick, int iter) {
		this.run = loop((Computation<Void>) tick, iter);
		this.setup = setup;
	}

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public Supplier<Runnable> tick() { return run; }

	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.prepareArguments(Stream.of(setup), map);
		ScopeLifecycle.prepareArguments(Stream.of(run), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		ScopeLifecycle.prepareScope(Stream.of(setup), manager);
		ScopeLifecycle.prepareScope(Stream.of(run), manager);
	}

	public void compile() {
		if (s != null || r != null) return;

		s = setup.get();
		r = run.get();

		// TODO  These probably should be removed completely
		if (s instanceof OperationAdapter && !((OperationAdapter) s).isCompiled()) ((OperationAdapter<?>) s).compile();
		if (r instanceof OperationAdapter && !((OperationAdapter) r).isCompiled()) ((OperationAdapter<?>) r).compile();
	}

	@Override
	public Runnable get() {
		compile();

		return () -> {
			s.run();
			r.run();
		};
	}

	public Runnable getContinue() {
		compile();
		return r;
	}

	@Override
	public Scope<Void> getScope() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Collection<Process<?>> getChildren() {
		return Stream.of(s, r)
				.map(o -> o instanceof Process ? (Process<?>) o : null)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
	}

	@Override
	public void compact() {
		Stream.of(setup).map(o -> o instanceof Compactable ? (Compactable) o : null)
				.filter(Objects::nonNull).forEach(Compactable::compact);
		Stream.of(run).map(o -> o instanceof Compactable ? (Compactable) o : null)
				.filter(Objects::nonNull).forEach(Compactable::compact);
	}

	public void destroy() {
		Stream.of(setup).map(o -> o instanceof OperationAdapter ? (OperationAdapter) o : null)
				.filter(Objects::nonNull)
				.forEach(OperationAdapter::destroy);
		Stream.of(setup).map(o -> o instanceof OperationList ? (OperationList) o : null)
				.filter(Objects::nonNull)
				.forEach(OperationList::destroy);

		Stream.of(run).map(o -> o instanceof OperationAdapter ? (OperationAdapter) o : null)
				.filter(Objects::nonNull)
				.forEach(OperationAdapter::destroy);
		Stream.of(run).map(o -> o instanceof OperationList ? (OperationList) o : null)
				.filter(Objects::nonNull)
				.forEach(OperationList::destroy);
	}
}
