/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.dsl;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Pins how the PDSL interpreter resolves names when one layer calls another, over the layers
 * of {@code test_program_scope.pdsl} and {@code test_program_data_scope.pdsl}.
 *
 * <p>On master (commit 74eb83317) a called layer was built in a fresh scope populated only
 * from its own arguments, and a call resolved built-ins before the program's own layers. Three
 * of these tests failed there:</p>
 * <ul>
 *   <li>{@link #nestedLayerWritesProgramState}: "Undefined identifier: 'rows'", because the
 *       state block was skipped for the called layer, whose arguments do not include it.</li>
 *   <li>{@link #nestedLayerReadsProgramDataBlock}: "Missing argument 'stacked' required by data
 *       block 'scope_data'", because the data block was re-bound from the called layer's
 *       arguments. A program with a data block could not call any of its layers from another.</li>
 *   <li>{@link #programLayerShadowsBuiltinOfSameName}: "softmax() expects 0 arguments, got 1",
 *       because the call reached the built-in instead of the program's layer.</li>
 * </ul>
 * <p>{@link #calledLayerDoesNotSeeCallerParameters} passed there and still passes: a called
 * layer is built inside the program's scope, not inside its caller's.</p>
 */
public class PdslProgramScopeTest extends TestSuiteBase {

	/** Classpath location of the program with a state block and the name-resolution layers. */
	private static final String FIXTURE = "/pdsl/test_program_scope.pdsl";

	/** Classpath location of the program with a data block. */
	private static final String DATA_FIXTURE = "/pdsl/test_program_data_scope.pdsl";

	/** Width of every layer's {@code [1, SIZE]} input. */
	private static final int SIZE = 4;

	/** Rows of the state table. */
	private static final int ROWS = 3;

	/**
	 * A layer called from another layer writes the table the program's state block declares:
	 * after one pass at position 1, row 1 holds the input and the other rows are untouched.
	 */
	@Test(timeout = 120000)
	public void nestedLayerWritesProgramState() {
		PackedCollection rows = new PackedCollection(shape(ROWS, SIZE));
		PackedCollection position = new PackedCollection(shape(1));
		Map<String, Object> args = arguments();
		args.put("rows", rows);
		args.put("position", position);
		CompiledModel model = compile(FIXTURE, "write_row_nested", args);

		position.fill(1);
		double[] x = input().toArray();
		double[] output = model.forward(input()).toArray();

		Assert.assertArrayEquals("the write passes its input through", x, output, 0.0);
		double[] table = rows.toArray();
		for (int r = 0; r < ROWS; r++) {
			for (int i = 0; i < SIZE; i++) {
				double expected = r == 1 ? x[i] : 0.0;
				Assert.assertEquals("row " + r + " element " + i, expected, table[r * SIZE + i], 0.0);
			}
		}
	}

	/**
	 * A layer called from another layer projects through the views the program's data block
	 * derives, exactly as the same layer built directly does.
	 */
	@Test(timeout = 120000)
	public void nestedLayerReadsProgramDataBlock() {
		Map<String, Object> args = arguments();
		args.put("stacked", stacked());
		double[] direct = compile(DATA_FIXTURE, "project_views", args).forward(input()).toArray();
		double[] nested = compile(DATA_FIXTURE, "project_views_nested", args).forward(input()).toArray();

		double[] w = stacked().toArray();
		double[] x = input().toArray();
		for (int n = 0; n < SIZE; n++) {
			double expected = 0.0;
			for (int i = 0; i < SIZE; i++) {
				expected += (w[n * SIZE + i] + w[(SIZE + n) * SIZE + i]) * x[i];
			}
			Assert.assertEquals("direct element " + n, expected, direct[n], 1e-5);
			Assert.assertEquals("nested element " + n, expected, nested[n], 1e-5);
		}
	}

	/**
	 * A call to {@code softmax} from a program that defines a {@code softmax} layer reaches that
	 * layer, which doubles its input, rather than the softmax built-in.
	 */
	@Test(timeout = 120000)
	public void programLayerShadowsBuiltinOfSameName() {
		double[] output = compile(FIXTURE, "calls_own_softmax", arguments()).forward(input()).toArray();

		double[] x = input().toArray();
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i, 2.0 * x[i], output[i], 1e-6);
		}
	}

	/**
	 * A called layer cannot read a parameter of the layer that called it: {@code factor} is in
	 * scope in {@code calls_with_factor_in_scope} but undefined in the {@code scaled_by_factor}
	 * layer it calls.
	 */
	@Test(timeout = 60000)
	public void calledLayerDoesNotSeeCallerParameters() {
		Map<String, Object> args = arguments();
		args.put("factor", 3.0);
		try {
			compile(FIXTURE, "calls_with_factor_in_scope", args);
			Assert.fail("A called layer should not see its caller's parameter 'factor'");
		} catch (PdslParseException expected) {
			Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("'factor'"));
		}
	}

	/** The arguments every layer of both fixtures takes: the width of its input. */
	private Map<String, Object> arguments() {
		Map<String, Object> args = new HashMap<>();
		args.put("size", SIZE);
		return args;
	}

	/** Builds {@code layer} of the {@code fixture} program for a {@code [1, SIZE]} input and compiles it. */
	private CompiledModel compile(String fixture, String layer, Map<String, Object> args) {
		PdslLoader loader = new PdslLoader();
		Model model = new Model(shape(1, SIZE));
		model.add(loader.buildLayer(loader.parseResource(fixture), layer, shape(1, SIZE), args));
		return model.compile();
	}

	/** The {@code [1, SIZE]} input: {@code -0.75, -0.25, 0.25, 0.75}. */
	private PackedCollection input() {
		return integers(0, SIZE).multiply(0.5).add(-0.75).reshape(shape(1, SIZE)).evaluate();
	}

	/** The stacked {@code [2 * SIZE, SIZE]} weight: element {@code i} is {@code sin(0.37 i + 0.2)}. */
	private PackedCollection stacked() {
		return sin(integers(0, 2 * SIZE * SIZE).multiply(0.37).add(0.2))
				.reshape(shape(2 * SIZE, SIZE)).evaluate();
	}
}
