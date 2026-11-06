/*
 * Copyright 2026 Michael Murray
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

package io.almostrealism.scope.test;

import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.scope.Variable;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Verifies the arguments that {@link Argument#dependencies(java.util.Collection)} derives from
 * the variables some code depends on, as every {@link io.almostrealism.scope.Scope} does for its
 * statements: each of the variables is {@link Expectation#WILL_EVALUATE evaluated} by the code,
 * and every variable it depends on, through any length of delegate chain, is
 * {@link Expectation#EVALUATE_AHEAD evaluated ahead} of it, in the order the chain is followed
 * and without an argument being repeated.
 */
public class ArgumentDependenciesTests extends TestSuiteBase {

	/**
	 * Variables with no dependencies each yield one argument, which the code evaluates, in the
	 * order of the variables.
	 */
	@Test(timeout = 30000)
	public void directVariablesAreEvaluated() {
		Variable<Double, ?> a = new Variable<>("a", Double.class);
		Variable<Double, ?> b = new Variable<>("b", Double.class);

		Assert.assertEquals(Arrays.asList(
						new Argument<>(a, Expectation.WILL_EVALUATE),
						new Argument<>(b, Expectation.WILL_EVALUATE)),
				Argument.dependencies(Arrays.asList(a, b)));
	}

	/**
	 * Each variable along a chain of delegates is evaluated ahead of the code, nearest first.
	 */
	@Test(timeout = 30000)
	public void delegateChainIsEvaluatedAhead() {
		Variable<Double, ?> root = new Variable<>("root", Double.class);
		Variable<Double, ?> view = delegating("view", root);
		Variable<Double, ?> nested = delegating("nested", view);

		Assert.assertEquals(Arrays.asList(
						new Argument<>(nested, Expectation.WILL_EVALUATE),
						new Argument<>(view, Expectation.EVALUATE_AHEAD),
						new Argument<>(root, Expectation.EVALUATE_AHEAD)),
				Argument.dependencies(Arrays.asList(nested)));
	}

	/**
	 * A dependency that several variables share, and a variable the code depends on more than
	 * once, are each listed once, where they are first reached.
	 */
	@Test(timeout = 30000)
	public void sharedDependencyIsListedOnce() {
		Variable<Double, ?> root = new Variable<>("root", Double.class);
		Variable<Double, ?> first = delegating("first", root);
		Variable<Double, ?> second = delegating("second", root);

		Assert.assertEquals(Arrays.asList(
						new Argument<>(first, Expectation.WILL_EVALUATE),
						new Argument<>(root, Expectation.EVALUATE_AHEAD),
						new Argument<>(second, Expectation.WILL_EVALUATE)),
				Argument.dependencies(Arrays.asList(first, second, first)));
	}

	/**
	 * A variable that the code depends on directly, and that another of its variables also
	 * depends on, is listed with both expectations; it is the duplicate removal a scope applies
	 * to its arguments that chooses between them.
	 */
	@Test(timeout = 30000)
	public void directDependencyKeepsBothExpectations() {
		Variable<Double, ?> root = new Variable<>("root", Double.class);
		Variable<Double, ?> view = delegating("view", root);

		Assert.assertEquals(Arrays.asList(
						new Argument<>(view, Expectation.WILL_EVALUATE),
						new Argument<>(root, Expectation.EVALUATE_AHEAD),
						new Argument<>(root, Expectation.WILL_EVALUATE)),
				Argument.dependencies(Arrays.asList(view, root)));
	}

	/**
	 * A null variable contributes no argument.
	 */
	@Test(timeout = 30000)
	public void nullVariablesAreSkipped() {
		Variable<Double, ?> a = new Variable<>("a", Double.class);

		Assert.assertEquals(Collections.singletonList(new Argument<>(a, Expectation.WILL_EVALUATE)),
				Argument.dependencies(Arrays.asList(null, a)));
	}

	/**
	 * A variable adds its arguments after those already collected, without repeating any of
	 * them.
	 */
	@Test(timeout = 30000)
	public void collectedArgumentsAreNotRepeated() {
		Variable<Double, ?> root = new Variable<>("root", Double.class);
		Variable<Double, ?> view = delegating("view", root);

		List<Argument<?>> args = new ArrayList<>();
		args.add(new Argument<>(root, Expectation.EVALUATE_AHEAD));
		view.appendArguments(Expectation.WILL_EVALUATE, args);

		Assert.assertEquals(Arrays.asList(
						new Argument<>(root, Expectation.EVALUATE_AHEAD),
						new Argument<>(view, Expectation.WILL_EVALUATE)),
				args);
	}

	/**
	 * The delegate of an argument is an argument, evaluated ahead, for the delegate of its
	 * variable; an argument whose variable has no delegate has none.
	 */
	@Test(timeout = 30000)
	public void delegateArgumentIsEvaluatedAhead() {
		Variable<Double, ?> root = new Variable<>("root", Double.class);
		Variable<Double, ?> view = delegating("view", root);

		Assert.assertEquals(new Argument<>(root, Expectation.EVALUATE_AHEAD),
				new Argument<>(view, Expectation.WILL_EVALUATE).getDelegate());
		Assert.assertNull(new Argument<>(root, Expectation.WILL_EVALUATE).getDelegate());
	}

	/**
	 * Creates a variable that delegates to another.
	 *
	 * @param name     the name of the variable
	 * @param delegate the variable to delegate to
	 * @return the delegating variable
	 */
	private static Variable<Double, ?> delegating(String name, Variable<Double, ?> delegate) {
		Variable<Double, Variable<Double, ?>> variable = new Variable<>(name, Double.class);
		variable.setDelegate(delegate);
		return variable;
	}
}
