/*
 * Copyright 2018 Michael Murray
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

package io.almostrealism.code;

import org.almostrealism.graph.ParameterizedGraph;
import org.almostrealism.graph.Parent;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Scope} is the container for {@link Variable}s, {@link Method}s, and other {@link Scope}s.
 *
 * @param <V>  Type of the {@link Variable}s that are used in the {@link Scope}. Usually this isn't
 *             used, since most {@link Scope}s need to support a range of {@link Variable} types, but
 *             it can be useful if there should be a restriction of some kind.
 */
public class Scope<V extends Variable> extends ArrayList<Scope<V>> implements ParameterizedGraph<Scope<V>, V>, Parent<Scope<V>> {
	private List<V> variables;
	private List<Method> methods;

	/**
	 * Creates an empty {@link Scope}.
	 */
	public Scope() {
		this.variables = new ArrayList<>();
		this.methods = new ArrayList<>();
	}

	/**
	 * @return  The {@link Variable}s in this {@link Scope}.
	 */
	public List<V> getVariables() { return variables; }

	/**
	 * @return  The {@link Method}s in this {@link Scope}.
	 */
	public List<Method> getMethods() { return methods; }

	/**
	 * @return  The inner {@link Scope}s contained by this {@link Scope}.
	 */
	public List<Scope<V>> getChildren() { return this; }

	/**
	 * Writes the {@link Variable}s and {@link Method}s for this {@link Scope}
	 * to the specified {@link CodePrintWriter}, then writes any {@link Scope}s
	 * included as children of this {@link Scope}.
	 *
	 * @param w  {@link CodePrintWriter} to use for encoding the {@link Scope}.
	 */
	public void write(CodePrintWriter w) {
		for (V v : getVariables()) { w.println(v); }
		for (Method m : getMethods()) { w.println(m); }
		for (Scope s : getChildren()) { s.write(w); }
	}
}
