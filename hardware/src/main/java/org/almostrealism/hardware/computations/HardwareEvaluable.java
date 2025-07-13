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

package org.almostrealism.hardware.computations;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArgumentList;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A {@link HardwareEvaluable} is a {@link Evaluable} that can be evaluated
 * for a {@link MemoryBank} with one operation. The default implementation
 * of this {@link MemoryBank} evaluation simply delegates to the normal
 * {@link #evaluate(Object[])} method for each element of the
 * {@link MemoryBank} (@see {@link DestinationEvaluable#evaluate(Object[])}).
 * <br>
 * {@link HardwareEvaluable} also optionally provides for short-circuiting,
 * when the process can be evaluated in an alternative way when not using
 * kernel operations.
 *
 * @author  Michael Murray
 */
public class HardwareEvaluable<T> implements Evaluable<T>, Runnable, ArgumentList<T> {
	private Supplier<Evaluable<T>> ev;
	private Evaluable<T> destination;
	private Evaluable<T> shortCircuit;

	private boolean isKernel;
	private ContextSpecific<Evaluable<T>> kernel;

	private UnaryOperator<MemoryBank<?>> destinationProcessor;

	public HardwareEvaluable(Supplier<Evaluable<T>> ev,
							 Evaluable<T> destination,
							 Evaluable<T> shortCircuit, boolean kernel) {
		this.ev = ev;
		this.destination = destination;
		this.shortCircuit = shortCircuit;
		this.isKernel = kernel;
		this.kernel = new DefaultContextSpecific<>(() -> ev.get());
	}

	public void setEvaluable(Supplier<Evaluable<T>> ev) { this.ev = ev; }

	public Evaluable<T> getDestination() {
		return destination;
	}

	public void setDestination(Evaluable<T> destination) {
		this.destination = destination;
	}

	public Evaluable<T> getShortCircuit() {
		return shortCircuit;
	}

	public void setShortCircuit(Evaluable<T> shortCircuit) {
		this.shortCircuit = shortCircuit;
	}

	@Override
	public boolean isConstant() {
		return getKernel().getValue().isConstant();
	}

	public boolean isKernel() { return isKernel; }

	public UnaryOperator<MemoryBank<?>> getDestinationProcessor() {
		return destinationProcessor;
	}

	public void setDestinationProcessor(UnaryOperator<MemoryBank<?>> destinationProcessor) {
		this.destinationProcessor = destinationProcessor;
	}

	@Override
	public Evaluable into(Object destination) {
		return withDestination((MemoryBank) destination);
	}

	public Evaluable<T> withDestination(MemoryBank destination) {
		if (destinationProcessor != null) {
			destination = destinationProcessor.apply(destination);
		}

		Evaluable ev = getKernel().getValue();
		if (ev instanceof HardwareEvaluable<?>) {
			return ((HardwareEvaluable) ev).withDestination(destination);
		}

		return new DestinationEvaluable<>(ev, destination);
	}

	public ContextSpecific<Evaluable<T>> getKernel() { return kernel; }

	@Override
	public Multiple<T> createDestination(int size) {
		return destination == null ? getKernel().getValue().createDestination(size) : destination.createDestination(size);
	}

	@Override
	public void run() { evaluate(); }

	@Override
	public T evaluate(Object... args) {
		if (Arrays.stream(args).anyMatch(i -> i instanceof Object[])) {
			throw new IllegalArgumentException("Embedded array provided to evaluate");
		}

		return shortCircuit == null ? getKernel().getValue().evaluate(args) : shortCircuit.evaluate(args);
	}

	@Override
	public int getArgsCount() {
		return ((ArgumentList) getKernel().getValue()).getArgsCount();
	}

	@Override
	public Collection<Argument<? extends T>> getChildren() {
		Evaluable ev = getKernel().getValue();

		if (ev instanceof ArgumentList) {
			return ((ArgumentList) ev).getChildren();
		}

		return Collections.emptyList();
	}
}
