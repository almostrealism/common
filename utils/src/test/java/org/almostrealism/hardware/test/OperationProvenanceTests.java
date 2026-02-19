/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Tests for provenance tracking during OperationList flattening.
 */
public class OperationProvenanceTests extends TestSuiteBase {

	@Test(timeout = 30000)
	public void testMetadataWithProvenance() {
		OperationMetadata original = new OperationMetadata("add", "add values");
		OperationMetadata withProv = original.withProvenance("layer X");

		assertEquals("layer X ==> add values", withProv.getShortDescription());
		assertEquals("add", withProv.getDisplayName());
	}

	@Test(timeout = 30000)
	public void testMetadataWithProvenanceNoShortDescription() {
		OperationMetadata original = new OperationMetadata("multiply", null);
		OperationMetadata withProv = original.withProvenance("parent op");

		assertEquals("parent op ==> multiply", withProv.getShortDescription());
		assertEquals("multiply", withProv.getDisplayName());
	}

	@Test(timeout = 30000)
	public void testNestedProvenanceChain() {
		OperationMetadata original = new OperationMetadata("add", null);
		OperationMetadata level1 = original.withProvenance("inner");
		OperationMetadata level2 = level1.withProvenance("outer");

		assertEquals("outer ==> inner ==> add", level2.getShortDescription());
	}

	@Test(timeout = 30000)
	public void testFlattenPreservesProvenance() {
		PackedCollection a = new PackedCollection(shape(10));
		PackedCollection b = new PackedCollection(shape(10));

		a.fill(pos -> Math.random());

		// Create an inner OperationList with a description
		OperationList inner = new OperationList("layer gradient");
		inner.add(a("inner add", traverseEach(p(b)), add(p(a), c(1.0))));

		// Create an outer OperationList
		OperationList outer = new OperationList("outer");
		outer.add(inner);

		// Flatten
		OperationList flat = outer.flatten();

		// The flattened list should have one operation with provenance
		assertEquals(1, flat.size());

		Supplier<Runnable> op = flat.get(0);
		assertTrue("Flattened operation should implement OperationInfo",
				op instanceof OperationInfo);

		OperationMetadata metadata = ((OperationInfo) op).getMetadata();
		assertNotNull("Metadata should not be null", metadata);
		assertEquals("layer gradient ==> inner add", metadata.getShortDescription());
	}

	@Test(timeout = 30000)
	public void testFlattenWithComputeRequirementsPreservesStructure() {
		PackedCollection a = new PackedCollection(shape(10));
		PackedCollection b = new PackedCollection(shape(10));

		a.fill(pos -> Math.random());

		// Create an inner OperationList with ComputeRequirements (should not be unwrapped)
		OperationList inner = new OperationList("layer with requirements");
		inner.add(a("inner op", traverseEach(p(b)), add(p(a), c(1.0))));
		inner.setComputeRequirements(java.util.List.of());  // Non-null but empty

		// Create an outer OperationList
		OperationList outer = new OperationList("outer");
		outer.add(inner);

		// Flatten
		OperationList flat = outer.flatten();

		// With ComputeRequirements, the inner list should remain as an OperationList
		assertEquals(1, flat.size());
		assertTrue("Should remain as OperationList", flat.get(0) instanceof OperationList);
	}

	@Test(timeout = 30000)
	public void testFlattenNoDescriptionNoProvenance() {
		PackedCollection a = new PackedCollection(shape(10));
		PackedCollection b = new PackedCollection(shape(10));

		a.fill(pos -> Math.random());

		// Create an inner OperationList WITHOUT a description
		OperationList inner = new OperationList();
		inner.add(a("original desc", traverseEach(p(b)), add(p(a), c(1.0))));

		// Create an outer OperationList
		OperationList outer = new OperationList("outer");
		outer.add(inner);

		// Flatten
		OperationList flat = outer.flatten();

		assertEquals(1, flat.size());

		Supplier<Runnable> op = flat.get(0);
		assertTrue(op instanceof OperationInfo);

		OperationMetadata metadata = ((OperationInfo) op).getMetadata();
		// No provenance added because inner had no description
		assertEquals("original desc", metadata.getShortDescription());
	}

	@Test(timeout = 30000)
	public void testDeepNestedFlatten() {
		PackedCollection a = new PackedCollection(shape(10));
		PackedCollection b = new PackedCollection(shape(10));

		a.fill(pos -> Math.random());

		// Create a deeply nested structure
		OperationList level3 = new OperationList("level 3");
		level3.add(a("add op", traverseEach(p(b)), add(p(a), c(1.0))));

		OperationList level2 = new OperationList("level 2");
		level2.add(level3);

		OperationList level1 = new OperationList("level 1");
		level1.add(level2);

		// Flatten
		OperationList flat = level1.flatten();

		assertEquals(1, flat.size());

		Supplier<Runnable> op = flat.get(0);
		assertTrue(op instanceof OperationInfo);

		OperationMetadata metadata = ((OperationInfo) op).getMetadata();
		// Should have full provenance chain
		assertEquals("level 2 ==> level 3 ==> add op", metadata.getShortDescription());
	}
}
