/*
 * Copyright 2023 Michael Murray
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

import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;

public interface Process<P extends Process<?, ?>, T> extends Node, Supplier<T>, Tree<P> {

	default Process<P, T> optimize() { return optimize(null); }

	default Process<P, T> optimize(ProcessContext context) {
		return this;
	}

	default Process<P, T> isolate() {
		return Process.of(this);
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
		if (process instanceof Process) {
			return ((Process<?, T>) process).isolate();
		} else {
			return Process.of(process);
		}
	}
}
