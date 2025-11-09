/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.streams.StreamingEvaluable;
import io.almostrealism.uml.Named;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.jocl.CLException;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class DestinationEvaluable<T extends MemoryBank> implements
		Evaluable<T>, StreamingEvaluable<T>, Runnable, Destroyable, ConsoleFeatures {
	private Evaluable<T> operation;
	private MemoryBank destination;

	private Executor executor;
	private Consumer<T> downstream;

	public DestinationEvaluable(Evaluable<T> operation, MemoryBank destination) {
		this(operation, destination, null, null);
	}

	public DestinationEvaluable(Evaluable<T> operation, MemoryBank destination,
								Executor executor) {
		this(operation, destination, executor, null);

		if (operation instanceof HardwareEvaluable) {
			// DestinationEvaluable is intended to be used only as an alternative
			// to HardwareEvaluable, when it is not possible to use it
			throw new UnsupportedOperationException();
		} else if (!(operation instanceof AcceleratedOperation<?>) && !(operation instanceof Provider)) {
			warn("Creating DestinationEvaluable for " + operation.getClass().getSimpleName() +
					" will not leverage hardware acceleration");
		}
	}

	private DestinationEvaluable(Evaluable<T> operation, MemoryBank destination,
								Executor executor, Consumer<T> downstream) {
		this.operation = operation;
		this.destination = destination;
		this.executor = executor;
		this.downstream = downstream;
	}

	@Override
	public void run() { evaluate(); }

	@Override
	public T evaluate(Object... args) {
		if (operation instanceof Provider<T>) {
			operation.into(destination).evaluate(args);
		} else if (operation instanceof AcceleratedOperation && ((AcceleratedOperation) operation).isKernel()) {
			AcceleratedProcessDetails details = ((AcceleratedOperation) operation)
					.apply(destination, Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
			details.getSemaphore().waitFor();
		} else {
			String name = operation instanceof Named ? ((Named) operation).getName() : OperationAdapter.operationName(null, getClass(), "function");
			if (HardwareOperator.enableVerboseLog) log("Evaluating " + name + " kernel...");

			boolean enableLog = false;

			for (int i = 0; i < destination.getCountLong(); i++) {
				T r = null;

				try {
					final int fi = i;
					Object o[] = Stream.of(args)
							.map(arg -> ((MemoryBank) arg).get(fi)).toArray();

					r = operation.evaluate(o);
					if (r == null) r = replaceNull(o);

					destination.set(i, r);
				} catch (UnsupportedOperationException e) {
					throw new HardwareException("i = " + i + " of " + destination.getCountLong() + ", r = " + r, e);
				} catch (CLException e) {
					throw new HardwareException("i = " + i + " of " + destination.getCountLong() + ", r = " + r, e);
				}

				if (enableLog && (i + 1) % 100 == 0) log((i + 1) + " kernel results collected");
			}
		}

		return (T) destination;
	}

	@Override
	public void request(Object[] args) {
		if (operation instanceof AcceleratedOperation && ((AcceleratedOperation) operation).isKernel()) {
			AcceleratedProcessDetails details = ((AcceleratedOperation) operation)
					.apply(destination, Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
			details.getSemaphore().onComplete(() -> downstream.accept((T) destination));
		} else {
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public void setDownstream(Consumer<T> consumer) {
		if (downstream != null) {
			throw new UnsupportedOperationException();
		}

		this.downstream = consumer;
	}

	@Override
	public StreamingEvaluable<T> async(Executor executor) {
		return new DestinationEvaluable<>(operation, destination, executor);
	}

	public T replaceNull(Object[] o) {
		if (operation instanceof NullProcessor) {
			return (T) ((NullProcessor) operation).replaceNull(o);
		} else {
			throw new NullPointerException();
		}
	}

	@Override
	public void destroy() {
		Destroyable.destroy(operation);
	}

	@Override
	public Console console() { return Hardware.console; }
}
