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

package io.almostrealism.scope;

import io.almostrealism.expression.Expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A runtime metric that tracks a counter expression and a set of named variables
 * to be logged at a configurable frequency during kernel execution.
 *
 * <p>A {@code Metric} is embedded in a {@link Scope} to instrument generated code.
 * At the configured log frequency the counter value and all monitored variable values
 * are emitted to the diagnostic output.</p>
 */
public class Metric {
	/** The expression whose value acts as the event counter for this metric. */
	private Expression<?> counter;

	/** How often (in terms of counter increments) the metric should be logged. */
	private int logFrequency;

	/** Named expressions monitored alongside the counter. */
	private Map<String, Expression<?>> variables;

	/**
	 * Creates a {@link Metric} with the given counter expression and log frequency.
	 *
	 * @param counter      the expression whose value is the event counter
	 * @param logFrequency the logging interval in counter units
	 */
	public Metric(Expression<?> counter, int logFrequency) {
		this.counter = counter;
		this.logFrequency = logFrequency;
		this.variables = new HashMap<>();
	}

	/**
	 * Returns all expressions that must be bound as kernel arguments for this metric,
	 * consisting of the counter followed by all monitored variable expressions.
	 *
	 * @return the list of argument expressions
	 */
	public List<Expression<?>> getArguments() {
		List<Expression<?>> refs = new ArrayList<>();
		refs.add(getCounter());
		refs.addAll(variables.values());
		return refs;
	}

	/**
	 * Returns the counter expression for this metric.
	 *
	 * @return the counter expression
	 */
	public Expression<?> getCounter() { return counter; }

	/**
	 * Returns the interval at which this metric is logged.
	 *
	 * @return the log frequency in counter units
	 */
	public int getLogFrequency() { return logFrequency; }

	/**
	 * Registers an additional expression to be monitored and logged with this metric.
	 *
	 * @param message  a human-readable label for the variable in log output
	 * @param variable the expression whose value is logged alongside the counter
	 */
	public void addMonitoredVariable(String message, Expression<?> variable) {
		variables.put(message, variable);
	}

	/**
	 * Returns the map of monitored variable labels to their expressions.
	 *
	 * @return the monitored variable map
	 */
	public Map<String, Expression<?>> getVariables() { return variables; }
}
