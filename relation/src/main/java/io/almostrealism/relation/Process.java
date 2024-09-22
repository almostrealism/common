/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.relation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A {@link Process} supplies some other value which is instrumentally useful in
 * completing a task, independent of whether the task requires computation to be
 * useful. A {@link Process} implementation may have dependent {@link Process}es,
 * forming a {@link Tree} which can be orchestrated to accomplish work of any kind.
 *
 * @param <P>  The type of the members of the {@link Process} tree.
 * @param <T>  The type of the result of the {@link Process}, which may either be
 *             some kind of directly useful value or instead, a mechanism for
 *             producing the ultimate result of the work.
 *
 * @author  Michael Murray
 */
public interface Process<P extends Process<?, ?>, T> extends Node, Supplier<T>, Tree<P> {
	List<Predicate<Process>> explicitIsolationTargets = new ArrayList<>();

	default Process<P, T> optimize() { return optimize(null); }

	default Process<P, T> optimize(ProcessContext context) {
		return this;
	}

	default Process<P, T> isolate() {
		return Process.of(this);
	}

	default boolean isIsolationTarget(ProcessContext context) {
		return false;
	}

	default long getOutputSize() {
		return 0;
	}

	static <T> long outputSize(T c) {
		if (c instanceof Process) {
			return ((Process<?, T>) c).getOutputSize();
		}

		return 0;
	}

	static <P extends Process<?, ?>, T> Process<P, T> of(Supplier<T> supplier) {
		return new Process<>() {
			@Override
			public Collection<P> getChildren() {
				return supplier instanceof Process ?
						((Process<P, T>) supplier).getChildren() : Collections.emptyList();
			}

			@Override
			public T get() {
				return supplier.get();
			}

			@Override
			public long getOutputSize() {
				return supplier instanceof Process ?
						((Process<P, T>) supplier).getOutputSize() :
						Process.super.getOutputSize();
			}
		};
	}

	static <T, P extends Supplier<T>> Supplier<T> optimized(P process) {
		if (process instanceof Process) {
			return ((Process<?, T>) process).optimize();
		} else {
			return process;
		}
	}

	static <T, P extends Supplier<T>> Supplier<T> isolated(P process) {
		if (!(process instanceof Process)) {
			return Process.of(process);
		}

		if (isolationPermitted(process)) {
			return ((Process<?, T>) process).isolate();
		}

		return process;
	}

	static <T, P extends Supplier<T>> boolean isolationPermitted(P process) {
		return !isExplicitIsolation() ||
				explicitIsolationTargets.stream().anyMatch(p -> p.test((Process) process));
	}

	static boolean isExplicitIsolation() {
		return !explicitIsolationTargets.isEmpty();
	}
}
