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

package org.almostrealism.graph.test;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.layers.BackPropagationCell;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Pins the receptor-replacement warning behavior that every
 * {@link Cell#setReceptor(Receptor)} implementation in the graph shares, now
 * consolidated onto {@link Cell#warnReceptorReplacement(ConsoleFeatures, Receptor)}.
 *
 * <p>The idiom was previously copied across every cell and block. Two copies had
 * diverged — the {@code Cell.of(Function)} transformation cell (the most common cell
 * factory) and {@link BackPropagationCell} warned on every replacement, omitting the
 * {@link Cell#cellWarnings} guard that the other copies applied. These tests confirm
 * the consolidated method gates on the flag and that the two formerly-divergent call
 * sites now warn identically to a gated sibling, while still storing the new receptor.</p>
 */
public class ReceptorReplacementWarningTest extends TestSuiteBase {

	/** Creates a no-op receptor usable as a replacement target. */
	private static Receptor<PackedCollection> receptor() {
		return protein -> new OperationList();
	}

	/**
	 * Replaces the receptor on the given cell (setting it twice so the second call is a
	 * replacement) and counts the receptor-replacement warnings that reach the root console.
	 */
	private int warningsOnReplacement(Cell<PackedCollection> cell) {
		List<String> captured = new ArrayList<>();
		Consumer<String> listener = line -> {
			if (line.contains("Replacing receptor")) captured.add(line);
		};

		Console.root().addListener(listener);

		try {
			cell.setReceptor(receptor());
			cell.setReceptor(receptor());
		} finally {
			Console.root().removeListener(listener);
		}

		return captured.size();
	}

	/**
	 * The consolidated method warns only when a receptor is already attached and cell
	 * warnings are enabled; a null existing receptor never warns regardless of the flag.
	 *
	 * <p>Both flag configurations are asserted explicitly through the flag-injecting
	 * overload so the enabled and disabled paths are each exercised, independently of the
	 * process-wide {@link Cell#cellWarnings} default that the JVM happened to start with.
	 * The no-flag convenience is then confirmed to delegate to that default.</p>
	 */
	@Test(timeout = 30000)
	public void methodGatesOnFlagAndNullReceptor() {
		List<String> captured = new ArrayList<>();
		ConsoleFeatures sink = new ConsoleFeatures() {
			@Override
			public void warn(String message) { captured.add(message); }
		};

		Cell.warnReceptorReplacement(sink, null, true);
		assertEquals(0, captured.size());

		Cell.warnReceptorReplacement(sink, receptor(), true);
		assertEquals(1, captured.size());

		captured.clear();

		Cell.warnReceptorReplacement(sink, receptor(), false);
		assertEquals(0, captured.size());

		Cell.warnReceptorReplacement(sink, null, false);
		assertEquals(0, captured.size());

		captured.clear();

		Cell.warnReceptorReplacement(sink, receptor());
		assertEquals(Cell.cellWarnings ? 1 : 0, captured.size());
	}

	/**
	 * The {@code Cell.of(Function)} transformation cell, formerly warning unconditionally,
	 * now warns identically to its gated {@code Cell.of(Factor)} sibling and still stores
	 * the latest receptor.
	 */
	@Test(timeout = 30000)
	public void transformationCellWarnsLikeGatedSibling() {
		Cell<PackedCollection> gated = Cell.of((Factor<PackedCollection>) in -> in);
		Cell<PackedCollection> consolidated =
				Cell.of((Function<Producer<PackedCollection>, Producer<PackedCollection>>) in -> in);

		assertEquals(warningsOnReplacement(gated), warningsOnReplacement(consolidated));

		Receptor<PackedCollection> latest = receptor();
		consolidated.setReceptor(receptor());
		consolidated.setReceptor(latest);
		assertTrue(latest == consolidated.getReceptor());
	}

	/**
	 * {@link BackPropagationCell}, formerly warning unconditionally, now warns identically
	 * to a gated sibling cell.
	 */
	@Test(timeout = 30000)
	public void backPropagationCellWarnsLikeGatedSibling() {
		Cell<PackedCollection> gated = Cell.of((Factor<PackedCollection>) in -> in);
		BackPropagationCell backprop = new BackPropagationCell("test",
				(gradient, input, next) -> new OperationList());

		assertEquals(warningsOnReplacement(gated), warningsOnReplacement(backprop));
	}
}
