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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Pins the {@code cache_read} built-in, and {@link LayerFeatures#cacheRead} behind it, through the
 * layers of {@code test_cache_primitives.pdsl}: the row it outputs follows the position from one
 * forward pass to the next, the stage input is not read, it composes with {@code concat_blocks}
 * into the {@code [x | h]} a recurrent cell consumes, and it reads a row before a later
 * {@code cache_write} of the same pass replaces it.
 *
 * <p>Every expectation is a value written into the cache by the test or by an earlier pass, so
 * the primitive is checked against its definition. Copies are exact, so the comparisons are too.</p>
 */
public class CacheReadPrimitiveTest extends TestSuiteBase implements LayerFeatures {

	/** Classpath location of the fixture program. */
	private static final String FIXTURE = "/pdsl/test_cache_primitives.pdsl";

	/**
	 * {@code cache_read} outputs row {@code position} of the cache, following the position as it
	 * changes between forward passes of one compiled model, whatever the input holds.
	 */
	@Test(timeout = 120000)
	public void cacheReadOutputsTheRowAtThePosition() {
		PackedCollection cache = integers(1, 7).reshape(shape(3, 2)).evaluate();
		PackedCollection position = new PackedCollection(shape(1));
		Map<String, Object> args = new HashMap<>();
		args.put("row_cache", cache);
		args.put("in_size", 4);
		args.put("position", p(position));
		CompiledModel model = compile("read_row", shape(4), args);

		double[][] rows = { { 1.0, 2.0 }, { 3.0, 4.0 }, { 5.0, 6.0 } };
		for (int row : new int[] { 2, 0, 1 }) {
			position.fill(row);
			PackedCollection input = pack(9.0 * row, -1.0, 7.0, 0.5);
			Assert.assertArrayEquals("row " + row, rows[row], model.forward(input).toArray(), 0.0);
		}
	}

	/**
	 * {@code concat_blocks} of {@code identity} and {@code cache_read} places the row after the
	 * input, and the row is the one in the cache when the pass runs, not when the layer was built.
	 */
	@Test(timeout = 120000)
	public void cacheReadAppendsTheCurrentRowAfterTheInput() {
		PackedCollection cache = new PackedCollection(shape(2, 3));
		Map<String, Object> args = new HashMap<>();
		args.put("row_cache", cache);
		args.put("size", 3);
		args.put("position", 1);
		CompiledModel model = compile("append_row", shape(3), args);

		PackedCollection input = pack(1.0, 2.0, 3.0);
		Assert.assertArrayEquals("fresh cache", new double[] { 1.0, 2.0, 3.0, 0.0, 0.0, 0.0 },
				model.forward(input).toArray(), 0.0);

		cache.setFrom(0, pack(10.0, 20.0, 30.0, 40.0, 50.0, 60.0), 0, 6);
		Assert.assertArrayEquals("after the cache changed", new double[] { 1.0, 2.0, 3.0, 40.0, 50.0, 60.0 },
				model.forward(input).toArray(), 0.0);
	}

	/**
	 * Within one pass {@code cache_read} sees the row as it was before a later {@code cache_write}
	 * of the same pass replaced it, and the next pass reads what that write stored: each pass of
	 * {@code exchange_row} returns the previous pass's input.
	 */
	@Test(timeout = 120000)
	public void cacheReadSeesTheRowBeforeALaterWriteOfTheSamePass() {
		PackedCollection cache = new PackedCollection(shape(3, 2));
		PackedCollection position = new PackedCollection(shape(1));
		Map<String, Object> args = new HashMap<>();
		args.put("row_cache", cache);
		args.put("size", 2);
		args.put("position", p(position));
		CompiledModel model = compile("exchange_row", shape(2), args);

		position.fill(2);
		Assert.assertArrayEquals("first pass", new double[] { 0.0, 0.0 },
				model.forward(pack(1.0, 2.0)).toArray(), 0.0);
		Assert.assertArrayEquals("second pass", new double[] { 1.0, 2.0 },
				model.forward(pack(3.0, 4.0)).toArray(), 0.0);

		position.clear();
		Assert.assertArrayEquals("another row", new double[] { 0.0, 0.0 },
				model.forward(pack(5.0, 6.0)).toArray(), 0.0);
		Assert.assertArrayEquals("cache", new double[] { 5.0, 6.0, 0.0, 0.0, 3.0, 4.0 },
				cache.toArray(), 0.0);
	}

	/**
	 * {@link LayerFeatures#cacheRead} rejects a cache that is not {@code [rows, rowSize]}, and the
	 * {@code cache_read} built-in rejects a call without a position.
	 */
	@Test(timeout = 60000)
	public void cacheReadRejectsInvalidArguments() {
		try {
			CollectionProducer cache = cp(new PackedCollection(shape(2, 3, 4)));
			cacheRead(shape(3), cache, p(new PackedCollection(shape(1))));
			Assert.fail("cacheRead should reject a cache that is not [rows, rowSize]");
		} catch (IllegalArgumentException expected) {
			// expected
		}

		try {
			Map<String, Object> args = new HashMap<>();
			args.put("row_cache", new PackedCollection(shape(2, 3)));
			args.put("in_size", 3);
			compile("read_without_position", shape(3), args);
			Assert.fail("cache_read() should reject a call without a position");
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/** Compiles one fixture layer on its own inside a {@link Model}. */
	private CompiledModel compile(String layer, TraversalPolicy inputShape, Map<String, Object> args) {
		PdslLoader loader = new PdslLoader();
		Model model = new Model(inputShape);
		model.add(loader.buildLayer(loader.parseResource(FIXTURE), layer, inputShape, args));
		return model.compile(false);
	}
}
