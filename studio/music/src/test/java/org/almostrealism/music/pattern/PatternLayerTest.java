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

package org.almostrealism.music.pattern;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tests for the hierarchy and range-query behaviour of {@link PatternLayer}.
 */
public class PatternLayerTest extends TestSuiteBase {

	/**
	 * Creates a layer holding one element at each of the given positions.
	 *
	 * @param choice    the choice owning the layer
	 * @param positions element positions in measures
	 * @return a new layer with a mutable element list
	 */
	private static PatternLayer layer(NoteAudioChoice choice, double... positions) {
		List<PatternElement> elements = new ArrayList<>();
		for (double p : positions) {
			elements.add(new PatternElement(new HashMap<>(), p));
		}

		return new PatternLayer(choice, elements);
	}

	/**
	 * Returns the positions of the given elements, in order.
	 *
	 * @param elements the elements to inspect
	 * @return their positions
	 */
	private static List<Double> positions(List<PatternElement> elements) {
		return elements.stream().map(PatternElement::getPosition).collect(Collectors.toList());
	}

	/** Range queries include the start and exclude the end. */
	@Test(timeout = 10000)
	public void elementsInRangeUseHalfOpenInterval() {
		PatternLayer layer = layer(null, 0.0, 0.5, 1.0, 1.5, 2.0);

		Assert.assertEquals(List.of(0.5, 1.0, 1.5), positions(layer.getElements(0.5, 2.0)));
		Assert.assertTrue(layer.getElements(2.5, 3.0).isEmpty());
		Assert.assertEquals(5, layer.getElements().size());
	}

	/** A chain of children reports its depth, tail and last parent consistently. */
	@Test(timeout = 10000)
	public void childChainNavigation() {
		PatternLayer root = layer(null, 0.0);
		Assert.assertEquals(1, root.depth());
		Assert.assertSame(root, root.getTail());
		Assert.assertNull("a single layer has no parent of a child", root.getLastParent());

		PatternLayer middle = layer(null, 1.0);
		PatternLayer leaf = layer(null, 2.0);
		root.setChild(middle);
		middle.setChild(leaf);

		Assert.assertEquals(3, root.depth());
		Assert.assertSame(leaf, root.getTail());
		Assert.assertSame(middle, root.getLastParent());
		Assert.assertSame(middle, root.getChild());

		root.getLastParent().setChild(null);
		Assert.assertEquals(2, root.depth());
		Assert.assertSame(middle, root.getTail());
		Assert.assertSame(root, root.getLastParent());
	}

	/** Collecting all elements walks every descendant layer within the range. */
	@Test(timeout = 10000)
	public void allElementsIncludeDescendants() {
		PatternLayer root = layer(null, 0.0, 3.0);
		PatternLayer child = layer(null, 0.5, 1.5);
		root.setChild(child);
		child.setChild(layer(null, 1.0, 4.0));

		Assert.assertEquals(List.of(0.0, 0.5, 1.5, 1.0),
				positions(root.getAllElements(0.0, 2.0)));
	}

	/** Elements are grouped by the choice owning each layer of the hierarchy. */
	@Test(timeout = 10000)
	public void elementsAreGroupedByChoice() {
		NoteAudioChoice kick = new NoteAudioChoice("kick");
		NoteAudioChoice snare = new NoteAudioChoice("snare");

		PatternLayer root = layer(kick, 0.0, 1.0);
		PatternLayer child = layer(snare, 0.5);
		root.setChild(child);
		child.setChild(layer(kick, 1.5, 8.0));

		Map<NoteAudioChoice, List<PatternElement>> result = new HashMap<>();
		root.putAllElementsByChoice(result, 0.0, 2.0);

		Assert.assertEquals(2, result.size());
		Assert.assertEquals(List.of(0.0, 1.0, 1.5), positions(result.get(kick)));
		Assert.assertEquals(List.of(0.5), positions(result.get(snare)));
	}

	/** An empty layer contributes nothing, even without a choice, but a populated one requires a choice. */
	@Test(timeout = 10000)
	public void groupingRequiresChoiceOnlyWhenPopulated() {
		Map<NoteAudioChoice, List<PatternElement>> result = new HashMap<>();
		new PatternLayer().putAllElementsByChoice(result, 0.0, 1.0);
		Assert.assertTrue(result.isEmpty());

		PatternLayer nullElements = new PatternLayer(new NoteAudioChoice("x"), null);
		nullElements.putAllElementsByChoice(result, 0.0, 1.0);
		Assert.assertTrue(result.isEmpty());

		try {
			layer(null, 0.0).putAllElementsByChoice(result, 0.0, 1.0);
			Assert.fail("a populated layer without a choice cannot be grouped");
		} catch (UnsupportedOperationException expected) {
			Assert.assertTrue(result.isEmpty());
		}
	}

	/** Trimming removes elements outside the half-open range in place. */
	@Test(timeout = 10000)
	public void trimRemovesOutOfRangeElements() {
		PatternLayer layer = layer(null, -0.5, 0.0, 1.0, 2.0, 3.0);
		layer.trim(2.0);
		Assert.assertEquals(List.of(0.0, 1.0), positions(layer.getElements()));

		PatternLayer windowed = layer(null, 0.0, 1.0, 2.0, 3.0);
		windowed.trim(1.0, 3.0);
		Assert.assertEquals(List.of(1.0, 2.0), positions(windowed.getElements()));
	}

	/** Automation parameters are assigned to every element of the layer. */
	@Test(timeout = 10000)
	public void automationParametersAreSharedByElements() {
		PatternLayer layer = layer(null, 0.0, 1.0);
		PackedCollection params = new PackedCollection(PatternLayerManager.AUTOMATION_GENE_LENGTH);
		layer.setAutomationParameters(params);

		layer.getElements().forEach(e -> Assert.assertSame(params, e.getAutomationParameters()));

		NoteAudioChoice choice = new NoteAudioChoice("c");
		layer.setChoice(choice);
		Assert.assertSame(choice, layer.getChoice());

		layer.setElements(new ArrayList<>());
		Assert.assertTrue(layer.getElements().isEmpty());
	}
}
