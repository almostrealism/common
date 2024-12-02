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

public class Metric {
	private Expression<?> counter;
	private int logFrequency;
	private Map<String, Expression<?>> variables;

	public Metric(Expression<?> counter, int logFrequency) {
		this.counter = counter;
		this.logFrequency = logFrequency;
		this.variables = new HashMap<>();
	}

	public List<Expression<?>> getArguments() {
		List<Expression<?>> refs = new ArrayList<>();
		refs.add(getCounter());
		refs.addAll(variables.values());
		return refs;
	}

	public Expression<?> getCounter() { return counter; }

	public int getLogFrequency() { return logFrequency; }

	public void addMonitoredVariable(String message, Expression<?> variable) {
		variables.put(message, variable);
	}

	public Map<String, Expression<?>> getVariables() { return variables; }
}
