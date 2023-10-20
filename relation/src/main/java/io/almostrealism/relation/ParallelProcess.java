/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.relation;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public interface ParallelProcess<P extends Process<?, ?>, T> extends Process<P, T>, Countable {
	boolean enableStrictIsolation = false;

	@Override
	default ParallelProcess<P, T> generate(List<P> children) {
		return (ParallelProcess<P, T>) Process.super.generate(children);
	}

	@Override
	default ParallelProcess<P, T> optimize() {
		Collection<? extends Process> children = getChildren();
		if (children.isEmpty()) return this;

		children = children.stream().map(Process::optimize).collect(Collectors.toList());

		long p = children.stream().mapToInt(ParallelProcess::count).distinct().count();
		long tot = children.stream().mapToInt(ParallelProcess::count).distinct().sum();
		if (p <= 1 && tot == getCount()) {
			return generate(children.stream().map(c -> (P) c).collect(Collectors.toList()));
		} else if (!enableStrictIsolation && getCount() > tot) {
			return generate(children.stream().map(c -> (P) c).collect(Collectors.toList()));
		}

		return generate(children.stream().map(c -> (P) c.isolate()).collect(Collectors.toList()));
	}

	default boolean isUniform() {
		long p = getChildren().stream().mapToInt(ParallelProcess::count).distinct().count();
		return p == 1;
	}

	static <T> int count(T c) {
		if (c instanceof Countable) {
			return ((Countable) c).getCount();
		}

		return 1;
	}

	static <T> boolean isFixedCount(T c) {
		if (c instanceof Countable) {
			return ((Countable) c).isFixedCount();
		}

		return true;
	}
}
