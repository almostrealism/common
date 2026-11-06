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

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.Statement;
import io.almostrealism.code.Termination;
import io.almostrealism.expression.ArrayDeclaration;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.scope.VariableReferenceSupport;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Verifies the names that {@link VariableReferenceSupport#appendAssignedNames(Set)} reports for
 * each kind of generated code: an assignment through its destination, a declaration of a local
 * array or an early exit nothing, a statement that gives no account of its writes the variables
 * it depends on, and a {@link Scope} the names its statements, declarations and children report.
 */
public class AssignedNamesTests extends TestSuiteBase {

	/**
	 * An assignment to a static reference changes the value it names, and the names it adds are
	 * added to those already collected.
	 */
	@Test(timeout = 30000)
	public void assignmentAddsItsDestination() {
		ExpressionAssignment<Double> assignment = new ExpressionAssignment<>(
				new StaticReference<>(Double.class, "x"), new StaticReference<>(Double.class, "y"));

		Set<String> names = new HashSet<>(Collections.singleton("existing"));
		assignment.appendAssignedNames(names);
		Assert.assertEquals(new HashSet<>(Arrays.asList("existing", "x")), names);
	}

	/**
	 * A static reference that tracks a variable adds the name of that variable as well as its
	 * own, since an assignment to it may change either.
	 */
	@Test(timeout = 30000)
	public void staticReferenceAddsItsReferent() {
		Variable<Double, ?> referent = new Variable<>("referent", Double.class);
		Set<String> names = new HashSet<>();
		new StaticReference<>(Double.class, "alias", referent).appendAssignedNames(names);
		Assert.assertEquals(new HashSet<>(Arrays.asList("alias", "referent")), names);
	}

	/**
	 * Declaring a local array and terminating execution assign no variable.
	 */
	@Test(timeout = 30000)
	public void declarationAndTerminationAddNothing() {
		Set<String> names = new HashSet<>();
		new ArrayDeclaration<>(Double.class, "local", new StaticReference<>(Integer.class, "n"))
				.appendAssignedNames(names);
		new Termination().appendAssignedNames(names);
		Assert.assertTrue(names.toString(), names.isEmpty());
	}

	/**
	 * A statement that does not account for its writes may assign any variable it depends on,
	 * and a variable without a name contributes nothing.
	 */
	@Test(timeout = 30000)
	public void unaccountedStatementAddsItsDependencies() {
		Statement<?> statement = new UnaccountedStatement(Arrays.asList(
				new Variable<>("first", Double.class),
				new Variable<>(null, Double.class),
				new Variable<>("second", Double.class)));

		Set<String> names = new HashSet<>();
		statement.appendAssignedNames(names);
		Assert.assertEquals(new HashSet<>(Arrays.asList("first", "second")), names);
	}

	/**
	 * A call as a statement adds the names of the variables its arguments refer to.
	 */
	@Test(timeout = 30000)
	public void callAddsTheNamesItRefersTo() {
		Variable<Double, ?> argument = new Variable<>("argument", Double.class);
		Method<Double> call = new Method<>("external",
				new StaticReference<>(Double.class, "arg", argument));

		Set<String> names = new HashSet<>();
		call.appendAssignedNames(names);
		Assert.assertTrue(names.toString(), names.contains("argument"));
		Assert.assertFalse(names.toString(), names.contains("external"));
	}

	/**
	 * A scope adds what its statements, its declarations and its child scopes assign, and
	 * nothing for code that only reads.
	 */
	@Test(timeout = 30000)
	public void scopeAddsItsStatementsDeclarationsAndChildren() {
		Scope<Void> scope = new Scope<>("parent");
		scope.assign(new StaticReference<>(Double.class, "statementTarget"),
				new StaticReference<>(Double.class, "onlyRead"));
		scope.getVariables().add(new ExpressionAssignment<>(true,
				new StaticReference<>(Double.class, "declared"), new DoubleConstant(1.0)));
		scope.getStatements().add(new Termination());

		Scope<Void> child = new Scope<>("child");
		child.assign(new StaticReference<>(Double.class, "childTarget"), new IntegerConstant(2));
		scope.getChildren().add(child);

		Set<String> names = new HashSet<>();
		scope.appendAssignedNames(names);
		Assert.assertEquals(new HashSet<>(Arrays.asList("statementTarget", "declared", "childTarget")), names);
	}

	/**
	 * A loop assigns its index, which it advances on every iteration, as well as what its body
	 * assigns, and a nested loop reports its index to the scope that contains it.
	 */
	@Test(timeout = 30000)
	public void loopAddsItsIndex() {
		Repeated<Void> inner = new Repeated<>(Variable.integer("j"),
				new StaticReference<>(Integer.class, "j").lessThan(new IntegerConstant(4)));
		Scope<Void> body = new Scope<>("body");
		body.assign(new StaticReference<>(Double.class, "bodyTarget"), new DoubleConstant(1.0));
		inner.getChildren().add(body);

		Scope<Void> outer = new Scope<>("outer");
		outer.getChildren().add(inner);

		Set<String> names = new HashSet<>();
		outer.appendAssignedNames(names);
		Assert.assertEquals(new HashSet<>(Arrays.asList("j", "bodyTarget")), names);
	}

	/**
	 * Only the statements and declarations the filter accepts contribute their names, in the
	 * scope and in its descendants alike, so a declaration accounted for by other means can be
	 * left out while a later reassignment of the same name still counts.
	 */
	@Test(timeout = 30000)
	public void excludedStatementsAddNothing() {
		ExpressionAssignment<Double> declaration = new ExpressionAssignment<>(true,
				new StaticReference<>(Double.class, "accumulator"), new DoubleConstant(0.0));

		Scope<Void> scope = new Scope<>("parent");
		scope.getStatements().add(declaration);

		Scope<Void> child = new Scope<>("child");
		ExpressionAssignment<Double> childDeclaration = new ExpressionAssignment<>(true,
				new StaticReference<>(Double.class, "local"), new DoubleConstant(2.0));
		child.getStatements().add(childDeclaration);
		scope.getChildren().add(child);

		Set<Statement<?>> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
		excluded.addAll(Arrays.asList(declaration, childDeclaration));
		Set<String> names = new HashSet<>();
		scope.appendAssignedNames(names, statement -> !excluded.contains(statement));
		Assert.assertTrue(names.toString(), names.isEmpty());

		scope.assign(new StaticReference<>(Double.class, "accumulator"),
				new StaticReference<>(Double.class, "increment"));
		scope.appendAssignedNames(names, statement -> !excluded.contains(statement));
		Assert.assertEquals(Collections.singleton("accumulator"), names);
	}

	/**
	 * An empty scope assigns nothing.
	 */
	@Test(timeout = 30000)
	public void emptyScopeAddsNothing() {
		Set<String> names = new HashSet<>();
		new Scope<Void>("empty").appendAssignedNames(names);
		Assert.assertTrue(names.toString(), names.isEmpty());
	}

	/**
	 * A statement that implements only what {@link Statement} requires, leaving what it writes
	 * and reads to the interface defaults.
	 */
	private static class UnaccountedStatement implements Statement<UnaccountedStatement> {
		/** The variables the statement depends on. */
		private final List<Variable<?, ?>> dependencies;

		/**
		 * Creates a statement that depends on the given variables.
		 *
		 * @param dependencies the variables the statement depends on
		 */
		UnaccountedStatement(List<Variable<?, ?>> dependencies) {
			this.dependencies = dependencies;
		}

		@Override
		public String getStatement(LanguageOperations lang) { return "external();"; }

		@Override
		public List<Variable<?, ?>> getDependencies() { return dependencies; }

		@Override
		public UnaccountedStatement simplify(KernelStructureContext context, int depth) { return this; }

		@Override
		public UnaccountedStatement replace(Expression target, Expression replacement) { return this; }
	}
}
