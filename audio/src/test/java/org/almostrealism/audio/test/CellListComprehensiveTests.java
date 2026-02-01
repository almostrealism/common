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

package org.almostrealism.audio.test;

import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CollectionCachedStateCell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Comprehensive tests for {@link CellList} features including:
 * <ul>
 *   <li>Parent-child tick ordering</li>
 *   <li>Requirements tick ordering</li>
 *   <li>Setup and lifecycle management</li>
 *   <li>Fluent API methods</li>
 *   <li>Frame-by-frame and multi-frame buffer scenarios</li>
 * </ul>
 *
 * These tests verify the critical tick ordering behavior documented in CellList:
 * Parents tick FIRST, then current cells, then requirements tick LAST.
 */
public class CellListComprehensiveTests extends TestSuiteBase implements CellFeatures, TestFeatures {

	// ========== TICK ORDERING TESTS ==========

	/**
	 * Test 1: Basic parent-child tick ordering - parent ticks before child
	 */
	@Test
	public void testParentTicksBeforeChild() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal parent = new TestTemporal("parent", tickOrder);
		TestTemporal child = new TestTemporal("child", tickOrder);

		CellList parentList = new CellList();
		parentList.add(parent);

		CellList childList = new CellList(parentList);
		childList.add(child);

		childList.tick().get().run();

		Assert.assertEquals(2, tickOrder.size());
		Assert.assertEquals("parent", tickOrder.get(0));
		Assert.assertEquals("child", tickOrder.get(1));
	}

	/**
	 * Test 2: Requirement ticks after cells
	 */
	@Test
	public void testRequirementTicksAfterCells() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal cell = new TestTemporal("cell", tickOrder);
		TestTemporal requirement = new TestTemporal("requirement", tickOrder);

		CellList cells = new CellList();
		cells.add(cell);
		cells.addRequirement(requirement);

		cells.tick().get().run();

		Assert.assertEquals(2, tickOrder.size());
		Assert.assertEquals("cell", tickOrder.get(0));
		Assert.assertEquals("requirement", tickOrder.get(1));
	}

	/**
	 * Test 3: Full hierarchy - parent, cell, requirement ordering
	 */
	@Test
	public void testFullHierarchyOrder() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal parent = new TestTemporal("parent", tickOrder);
		TestTemporal cell = new TestTemporal("cell", tickOrder);
		TestTemporal requirement = new TestTemporal("requirement", tickOrder);

		CellList parentList = new CellList();
		parentList.add(parent);

		CellList childList = new CellList(parentList);
		childList.add(cell);
		childList.addRequirement(requirement);

		childList.tick().get().run();

		Assert.assertEquals(3, tickOrder.size());
		Assert.assertEquals("parent", tickOrder.get(0));
		Assert.assertEquals("cell", tickOrder.get(1));
		Assert.assertEquals("requirement", tickOrder.get(2));
	}

	/**
	 * Test 4: Multiple parents tick in order
	 */
	@Test
	public void testMultipleParentsOrder() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal parent1 = new TestTemporal("parent1", tickOrder);
		TestTemporal parent2 = new TestTemporal("parent2", tickOrder);
		TestTemporal child = new TestTemporal("child", tickOrder);

		CellList parentList1 = new CellList();
		parentList1.add(parent1);

		CellList parentList2 = new CellList();
		parentList2.add(parent2);

		CellList childList = new CellList(List.of(parentList1, parentList2));
		childList.add(child);

		childList.tick().get().run();

		Assert.assertEquals(3, tickOrder.size());
		Assert.assertEquals("parent1", tickOrder.get(0));
		Assert.assertEquals("parent2", tickOrder.get(1));
		Assert.assertEquals("child", tickOrder.get(2));
	}

	/**
	 * Test 5: Nested parent hierarchy (grandparent -> parent -> child)
	 */
	@Test
	public void testNestedParentHierarchy() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal grandparent = new TestTemporal("grandparent", tickOrder);
		TestTemporal parent = new TestTemporal("parent", tickOrder);
		TestTemporal child = new TestTemporal("child", tickOrder);

		CellList grandparentList = new CellList();
		grandparentList.add(grandparent);

		CellList parentList = new CellList(grandparentList);
		parentList.add(parent);

		CellList childList = new CellList(parentList);
		childList.add(child);

		childList.tick().get().run();

		Assert.assertEquals(3, tickOrder.size());
		Assert.assertEquals("grandparent", tickOrder.get(0));
		Assert.assertEquals("parent", tickOrder.get(1));
		Assert.assertEquals("child", tickOrder.get(2));
	}

	/**
	 * Test 6: Multiple requirements tick in order
	 */
	@Test
	public void testMultipleRequirementsOrder() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal cell = new TestTemporal("cell", tickOrder);
		TestTemporal req1 = new TestTemporal("req1", tickOrder);
		TestTemporal req2 = new TestTemporal("req2", tickOrder);

		CellList cells = new CellList();
		cells.add(cell);
		cells.addRequirement(req1);
		cells.addRequirement(req2);

		cells.tick().get().run();

		Assert.assertEquals(3, tickOrder.size());
		Assert.assertEquals("cell", tickOrder.get(0));
		Assert.assertEquals("req1", tickOrder.get(1));
		Assert.assertEquals("req2", tickOrder.get(2));
	}

	/**
	 * Test 7: addRequirements varargs method
	 */
	@Test
	public void testAddRequirementsVarargs() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal cell = new TestTemporal("cell", tickOrder);
		TestTemporal req1 = new TestTemporal("req1", tickOrder);
		TestTemporal req2 = new TestTemporal("req2", tickOrder);
		TestTemporal req3 = new TestTemporal("req3", tickOrder);

		CellList cells = new CellList();
		cells.add(cell);
		cells.addRequirements(req1, req2, req3);

		cells.tick().get().run();

		Assert.assertEquals(4, tickOrder.size());
		Assert.assertEquals("cell", tickOrder.get(0));
		Assert.assertEquals("req1", tickOrder.get(1));
	}

	/**
	 * Test 8: Empty CellList tick does not throw
	 */
	@Test
	public void testEmptyCellListTick() {
		CellList cells = new CellList();
		cells.tick().get().run();
		// Should not throw
	}

	/**
	 * Test 9: CellList with only requirements
	 */
	@Test
	public void testOnlyRequirements() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal req = new TestTemporal("req", tickOrder);

		CellList cells = new CellList();
		cells.addRequirement(req);

		cells.tick().get().run();

		Assert.assertEquals(1, tickOrder.size());
		Assert.assertEquals("req", tickOrder.get(0));
	}

	/**
	 * Test 10: CellList with only parent
	 */
	@Test
	public void testOnlyParent() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal parent = new TestTemporal("parent", tickOrder);

		CellList parentList = new CellList();
		parentList.add(parent);

		CellList childList = new CellList(parentList);
		// Child has no cells

		childList.tick().get().run();

		Assert.assertEquals(1, tickOrder.size());
		Assert.assertEquals("parent", tickOrder.get(0));
	}

	// ========== SETUP AND LIFECYCLE TESTS ==========

	/**
	 * Test 11: Setup is called on all cells
	 */
	@Test
	public void testSetupCalledOnAllCells() {
		AtomicInteger setupCount = new AtomicInteger(0);

		TestSetupCell cell1 = new TestSetupCell(setupCount);
		TestSetupCell cell2 = new TestSetupCell(setupCount);

		CellList cells = new CellList();
		cells.add(cell1);
		cells.add(cell2);

		cells.setup().get().run();

		Assert.assertEquals(2, setupCount.get());
	}

	/**
	 * Test 12: Setup follows parent hierarchy
	 */
	@Test
	public void testSetupFollowsHierarchy() {
		List<String> setupOrder = new ArrayList<>();

		TestSetupTemporal parent = new TestSetupTemporal("parent", setupOrder);
		TestSetupTemporal child = new TestSetupTemporal("child", setupOrder);

		CellList parentList = new CellList();
		parentList.add(parent);

		CellList childList = new CellList(parentList);
		childList.add(child);

		childList.setup().get().run();

		Assert.assertEquals(2, setupOrder.size());
		Assert.assertEquals("parent", setupOrder.get(0));
		Assert.assertEquals("child", setupOrder.get(1));
	}

	/**
	 * Test 13: addSetup adds explicit setup operation
	 */
	@Test
	public void testAddSetup() {
		AtomicInteger setupCount = new AtomicInteger(0);

		CellList cells = new CellList();
		Runnable inc = () -> setupCount.incrementAndGet();
		Supplier<Runnable> supplier = () -> inc;
		cells.addSetup(() -> supplier);

		cells.setup().get().run();

		Assert.assertEquals(1, setupCount.get());
	}

	/**
	 * Test 14: Reset is called on all cells
	 */
	@Test
	public void testResetCalledOnAllCells() {
		AtomicInteger resetCount = new AtomicInteger(0);

		TestResetCell cell1 = new TestResetCell(resetCount);
		TestResetCell cell2 = new TestResetCell(resetCount);

		CellList cells = new CellList();
		cells.add(cell1);
		cells.add(cell2);

		cells.reset();

		Assert.assertEquals(2, resetCount.get());
	}

	/**
	 * Test 15: Finals are called during reset
	 */
	@Test
	public void testFinalsCalledDuringReset() {
		AtomicInteger finalCount = new AtomicInteger(0);

		CellList cells = new CellList();
		cells.getFinals().add(() -> finalCount.incrementAndGet());
		cells.getFinals().add(() -> finalCount.incrementAndGet());

		cells.reset();

		Assert.assertEquals(2, finalCount.get());
	}

	/**
	 * Test 16: addData tracks collections for destroy
	 */
	@Test
	public void testAddDataTracksForDestroy() {
		PackedCollection col1 = new PackedCollection(10);
		PackedCollection col2 = new PackedCollection(20);

		CellList cells = new CellList();
		cells.addData(col1, col2);

		cells.destroy();

		// After destroy, collections should be released
		// We can't directly test destruction, but no exception means success
	}

	// ========== FLUENT API TESTS ==========

	/**
	 * Test 17: and() combines two lists
	 */
	@Test
	public void testAndCombinesLists() {
		CellList list1 = new CellList();
		list1.add(new TestTemporal("a", new ArrayList<>()));

		CellList list2 = new CellList();
		list2.add(new TestTemporal("b", new ArrayList<>()));

		CellList combined = list1.and(list2);

		// Combined list should reference both
		Assert.assertTrue(combined.getParents().size() >= 2 ||
				combined.getAll().size() >= 2);
	}

	/**
	 * Test 18: sum() creates summing cell
	 */
	@Test
	public void testSumCreatesCell() {
		CellList cells = new CellList();
		cells.add(new TestTemporal("a", new ArrayList<>()));
		cells.add(new TestTemporal("b", new ArrayList<>()));

		CellList summed = cells.sum();

		Assert.assertNotNull(summed);
		Assert.assertTrue(summed.getParents().contains(cells));
	}

	/**
	 * Test 19: map() creates mapped cells
	 */
	@Test
	public void testMapCreatesMappedCells() {
		CellList source = new CellList();
		source.add(new TestTemporal("a", new ArrayList<>()));
		source.add(new TestTemporal("b", new ArrayList<>()));

		CellList mapped = source.map(i -> new TestTemporal("mapped" + i, new ArrayList<>()));

		Assert.assertNotNull(mapped);
	}

	/**
	 * Test 20: branch() creates multiple branches
	 */
	@Test
	public void testBranchCreatesMultiple() {
		CellList source = new CellList();
		source.add(new TestTemporal("source", new ArrayList<>()));

		CellList[] branches = source.branch(
				i -> new TestTemporal("branch1", new ArrayList<>()),
				i -> new TestTemporal("branch2", new ArrayList<>())
		);

		Assert.assertEquals(2, branches.length);
	}

	/**
	 * Test 21: getParents() returns correct parents
	 */
	@Test
	public void testGetParentsReturnsCorrect() {
		CellList parent1 = new CellList();
		CellList parent2 = new CellList();

		CellList child = new CellList(parent1, parent2);

		List<CellList> parents = child.getParents();
		Assert.assertEquals(2, parents.size());
		Assert.assertTrue(parents.contains(parent1));
		Assert.assertTrue(parents.contains(parent2));
	}

	/**
	 * Test 22: getRequirements() returns requirements
	 */
	@Test
	public void testGetRequirementsReturns() {
		TestTemporal req = new TestTemporal("req", new ArrayList<>());

		CellList cells = new CellList();
		cells.addRequirement(req);

		Assert.assertEquals(1, cells.getRequirements().size());
	}

	/**
	 * Test 23: getAll() collects all cells from hierarchy
	 */
	@Test
	public void testGetAllCollectsHierarchy() {
		TestTemporal parent = new TestTemporal("parent", new ArrayList<>());
		TestTemporal child = new TestTemporal("child", new ArrayList<>());

		CellList parentList = new CellList();
		parentList.add(parent);

		CellList childList = new CellList(parentList);
		childList.add(child);

		Assert.assertEquals(2, childList.getAll().size());
	}

	/**
	 * Test 24: getAllTemporals() collects in correct order
	 */
	@Test
	public void testGetAllTemporalsOrder() {
		TestTemporal parent = new TestTemporal("parent", new ArrayList<>());
		TestTemporal child = new TestTemporal("child", new ArrayList<>());
		TestTemporal req = new TestTemporal("req", new ArrayList<>());

		CellList parentList = new CellList();
		parentList.add(parent);

		CellList childList = new CellList(parentList);
		childList.add(child);
		childList.addRequirement(req);

		Assert.assertEquals(3, childList.getAllTemporals().size());
	}

	/**
	 * Test 25: getAllSetup() collects all setup operations
	 */
	@Test
	public void testGetAllSetupCollects() {
		TestSetupTemporal parent = new TestSetupTemporal("parent", new ArrayList<>());
		TestSetupTemporal child = new TestSetupTemporal("child", new ArrayList<>());

		CellList parentList = new CellList();
		parentList.add(parent);

		CellList childList = new CellList(parentList);
		childList.add(child);

		Assert.assertEquals(2, childList.getAllSetup().size());
	}

	// ========== ROOT TESTS ==========

	/**
	 * Test 26: addRoot adds both as root and cell
	 */
	@Test
	public void testAddRootAddsAsBoth() {
		TestTemporal cell = new TestTemporal("root", new ArrayList<>());

		CellList cells = new CellList();
		cells.addRoot(cell);

		Assert.assertEquals(1, cells.size());
		Assert.assertEquals(1, cells.getAllRoots().size());
	}

	/**
	 * Test 27: getAllRoots() collects from hierarchy
	 */
	@Test
	public void testGetAllRootsCollectsHierarchy() {
		TestTemporal parentRoot = new TestTemporal("parentRoot", new ArrayList<>());
		TestTemporal childRoot = new TestTemporal("childRoot", new ArrayList<>());

		CellList parentList = new CellList();
		parentList.addRoot(parentRoot);

		CellList childList = new CellList(parentList);
		childList.addRoot(childRoot);

		Assert.assertEquals(2, childList.getAllRoots().size());
	}

	/**
	 * Test 28: Roots receive push during tick
	 */
	@Test
	public void testRootsReceivePush() {
		AtomicInteger pushCount = new AtomicInteger(0);

		TestPushReceptor root = new TestPushReceptor(pushCount);

		CellList cells = new CellList();
		cells.addRoot(root);

		cells.tick().get().run();

		Assert.assertTrue(pushCount.get() > 0);
	}

	// ========== FRAME-BY-FRAME ITERATION TESTS ==========

	/**
	 * Test 29: Frame-by-frame single iteration
	 */
	@Test
	public void testFrameByFrameSingleIteration() {
		AtomicInteger tickCount = new AtomicInteger(0);

		CountingTemporal cell = new CountingTemporal(tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		TemporalRunner runner = new TemporalRunner(cells, 1);
		runner.get().run();

		Assert.assertEquals(1, tickCount.get());
	}

	/**
	 * Test 30: Frame-by-frame 10 iterations
	 */
	@Test
	public void testFrameByFrame10Iterations() {
		AtomicInteger tickCount = new AtomicInteger(0);

		CountingTemporal cell = new CountingTemporal(tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		TemporalRunner runner = new TemporalRunner(cells, 10);
		runner.get().run();

		Assert.assertEquals(10, tickCount.get());
	}

	/**
	 * Test 31: Frame-by-frame 100 iterations
	 */
	@Test
	public void testFrameByFrame100Iterations() {
		AtomicInteger tickCount = new AtomicInteger(0);

		CountingTemporal cell = new CountingTemporal(tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		TemporalRunner runner = new TemporalRunner(cells, 100);
		runner.get().run();

		Assert.assertEquals(100, tickCount.get());
	}

	/**
	 * Test 32: Frame-by-frame 1000 iterations
	 */
	@Test
	public void testFrameByFrame1000Iterations() {
		AtomicInteger tickCount = new AtomicInteger(0);

		CountingTemporal cell = new CountingTemporal(tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		TemporalRunner runner = new TemporalRunner(cells, 1000);
		runner.get().run();

		Assert.assertEquals(1000, tickCount.get());
	}

	/**
	 * Test 33: Frame-by-frame with multiple cells
	 */
	@Test
	public void testFrameByFrameMultipleCells() {
		AtomicInteger tickCount1 = new AtomicInteger(0);
		AtomicInteger tickCount2 = new AtomicInteger(0);

		CountingTemporal cell1 = new CountingTemporal(tickCount1);
		CountingTemporal cell2 = new CountingTemporal(tickCount2);

		CellList cells = new CellList();
		cells.add(cell1);
		cells.add(cell2);

		TemporalRunner runner = new TemporalRunner(cells, 50);
		runner.get().run();

		Assert.assertEquals(50, tickCount1.get());
		Assert.assertEquals(50, tickCount2.get());
	}

	/**
	 * Test 34: Frame-by-frame with parent hierarchy
	 */
	@Test
	public void testFrameByFrameWithHierarchy() {
		AtomicInteger parentCount = new AtomicInteger(0);
		AtomicInteger childCount = new AtomicInteger(0);

		CountingTemporal parentCell = new CountingTemporal(parentCount);
		CountingTemporal childCell = new CountingTemporal(childCount);

		CellList parentList = new CellList();
		parentList.add(parentCell);

		CellList childList = new CellList(parentList);
		childList.add(childCell);

		TemporalRunner runner = new TemporalRunner(childList, 25);
		runner.get().run();

		Assert.assertEquals(25, parentCount.get());
		Assert.assertEquals(25, childCount.get());
	}

	// ========== MULTI-FRAME BUFFER TESTS ==========

	/**
	 * Test 35: Multi-frame buffer 512 frames
	 */
	@Test
	public void testMultiFrameBuffer512() {
		AtomicInteger tickCount = new AtomicInteger(0);

		CountingTemporal cell = new CountingTemporal(tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		TemporalRunner runner = new TemporalRunner(cells, 512);
		runner.get().run();

		Assert.assertEquals(512, tickCount.get());
	}

	/**
	 * Test 36: Multi-frame buffer 1024 frames
	 */
	@Test
	public void testMultiFrameBuffer1024() {
		AtomicInteger tickCount = new AtomicInteger(0);

		CountingTemporal cell = new CountingTemporal(tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		TemporalRunner runner = new TemporalRunner(cells, 1024);
		runner.get().run();

		Assert.assertEquals(1024, tickCount.get());
	}

	/**
	 * Test 37: Multi-frame buffer 2048 frames
	 */
	@Test
	public void testMultiFrameBuffer2048() {
		AtomicInteger tickCount = new AtomicInteger(0);

		CountingTemporal cell = new CountingTemporal(tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		TemporalRunner runner = new TemporalRunner(cells, 2048);
		runner.get().run();

		Assert.assertEquals(2048, tickCount.get());
	}

	/**
	 * Test 38: Multi-frame multiple batches
	 */
	@Test
	public void testMultiFrameMultipleBatches() {
		AtomicInteger tickCount = new AtomicInteger(0);

		CountingTemporal cell = new CountingTemporal(tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		TemporalRunner runner = new TemporalRunner(cells, 256);

		// Run 4 batches
		runner.get().run();  // First batch with setup
		runner.getContinue().run();  // Second batch
		runner.getContinue().run();  // Third batch
		runner.getContinue().run();  // Fourth batch

		Assert.assertEquals(256 * 4, tickCount.get());
	}

	/**
	 * Test 39: Multi-frame with getContinue() skips setup
	 */
	@Test
	public void testMultiFrameGetContinueSkipsSetup() {
		AtomicInteger setupCount = new AtomicInteger(0);
		AtomicInteger tickCount = new AtomicInteger(0);

		SetupCountingTemporal cell = new SetupCountingTemporal(setupCount, tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		TemporalRunner runner = new TemporalRunner(cells, 100);

		runner.get().run();  // Setup + 100 ticks
		runner.getContinue().run();  // 100 more ticks, no setup

		Assert.assertEquals(1, setupCount.get());
		Assert.assertEquals(200, tickCount.get());
	}

	// ========== COLLECTOR TESTS ==========

	/**
	 * Test 40: collector() collects cells into CellList
	 */
	@Test
	public void testCollectorCollectsCells() {
		List<Cell<PackedCollection>> cells = List.of(
				new TestTemporal("a", new ArrayList<>()),
				new TestTemporal("b", new ArrayList<>()),
				new TestTemporal("c", new ArrayList<>())
		);

		CellList collected = cells.stream().collect(CellList.collector());

		Assert.assertEquals(3, collected.size());
	}

	// ========== COMPLEX SCENARIO TESTS ==========

	/**
	 * Test 41: Complex hierarchy with all features
	 */
	@Test
	public void testComplexHierarchy() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal grandparent = new TestTemporal("grandparent", tickOrder);
		TestTemporal parent1 = new TestTemporal("parent1", tickOrder);
		TestTemporal parent2 = new TestTemporal("parent2", tickOrder);
		TestTemporal child = new TestTemporal("child", tickOrder);
		TestTemporal req1 = new TestTemporal("req1", tickOrder);
		TestTemporal req2 = new TestTemporal("req2", tickOrder);

		CellList grandparentList = new CellList();
		grandparentList.add(grandparent);

		CellList parent1List = new CellList(grandparentList);
		parent1List.add(parent1);

		CellList parent2List = new CellList();
		parent2List.add(parent2);

		CellList childList = new CellList(List.of(parent1List, parent2List));
		childList.add(child);
		childList.addRequirement(req1);
		childList.addRequirement(req2);

		childList.tick().get().run();

		// Expected order: grandparent -> parent1 -> parent2 -> child -> req1 -> req2
		Assert.assertEquals(6, tickOrder.size());
		Assert.assertEquals("grandparent", tickOrder.get(0));
		Assert.assertEquals("parent1", tickOrder.get(1));
		Assert.assertEquals("parent2", tickOrder.get(2));
		Assert.assertEquals("child", tickOrder.get(3));
		Assert.assertEquals("req1", tickOrder.get(4));
		Assert.assertEquals("req2", tickOrder.get(5));
	}

	/**
	 * Test 42: Deep nesting (5 levels)
	 */
	@Test
	public void testDeepNesting5Levels() {
		List<String> tickOrder = new ArrayList<>();

		CellList level1 = new CellList();
		level1.add(new TestTemporal("level1", tickOrder));

		CellList level2 = new CellList(level1);
		level2.add(new TestTemporal("level2", tickOrder));

		CellList level3 = new CellList(level2);
		level3.add(new TestTemporal("level3", tickOrder));

		CellList level4 = new CellList(level3);
		level4.add(new TestTemporal("level4", tickOrder));

		CellList level5 = new CellList(level4);
		level5.add(new TestTemporal("level5", tickOrder));

		level5.tick().get().run();

		Assert.assertEquals(5, tickOrder.size());
		for (int i = 0; i < 5; i++) {
			Assert.assertEquals("level" + (i + 1), tickOrder.get(i));
		}
	}

	/**
	 * Test 43: Diamond inheritance pattern
	 */
	@Test
	public void testDiamondPattern() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal top = new TestTemporal("top", tickOrder);
		TestTemporal left = new TestTemporal("left", tickOrder);
		TestTemporal right = new TestTemporal("right", tickOrder);
		TestTemporal bottom = new TestTemporal("bottom", tickOrder);

		CellList topList = new CellList();
		topList.add(top);

		CellList leftList = new CellList(topList);
		leftList.add(left);

		CellList rightList = new CellList(topList);
		rightList.add(right);

		CellList bottomList = new CellList(List.of(leftList, rightList));
		bottomList.add(bottom);

		bottomList.tick().get().run();

		// top should appear twice (once from each branch)
		Assert.assertTrue(tickOrder.size() >= 4);
	}

	/**
	 * Test 44: Empty parent list
	 */
	@Test
	public void testEmptyParentList() {
		List<String> tickOrder = new ArrayList<>();

		TestTemporal cell = new TestTemporal("cell", tickOrder);

		CellList cells = new CellList(List.of());
		cells.add(cell);

		cells.tick().get().run();

		Assert.assertEquals(1, tickOrder.size());
		Assert.assertEquals("cell", tickOrder.get(0));
	}

	/**
	 * Test 45: Mixed cell types
	 */
	@Test
	public void testMixedCellTypes() {
		AtomicInteger temporalCount = new AtomicInteger(0);
		AtomicInteger nonTemporalCount = new AtomicInteger(0);

		CountingTemporal temporal = new CountingTemporal(temporalCount);
		NonTemporalCell nonTemporal = new NonTemporalCell(nonTemporalCount);

		CellList cells = new CellList();
		cells.add(temporal);
		cells.add(nonTemporal);

		cells.tick().get().run();

		Assert.assertEquals(1, temporalCount.get());  // Only temporal ticks
		Assert.assertEquals(0, nonTemporalCount.get());  // Non-temporal doesn't tick
	}

	/**
	 * Test 46: Requirements from different types
	 */
	@Test
	public void testRequirementsFromTypes() {
		AtomicInteger setupReqCount = new AtomicInteger(0);
		AtomicInteger tickCount = new AtomicInteger(0);

		SetupCountingTemporal requirement = new SetupCountingTemporal(setupReqCount, tickCount);

		CellList cells = new CellList();
		cells.addRequirement(requirement);

		// Setup phase
		cells.setup().get().run();
		Assert.assertEquals(1, setupReqCount.get());

		// Tick phase
		cells.tick().get().run();
		Assert.assertEquals(1, tickCount.get());
	}

	// ========== EDGE CASE TESTS ==========

	/**
	 * Test 47: Null-safe operations
	 */
	@Test
	public void testNullSafeOperations() {
		CellList cells = new CellList();

		// These should not throw
		cells.getParents();
		cells.getRequirements();
		cells.getFinals();
		cells.getAll();
		cells.getAllTemporals();
		cells.getAllSetup();
		cells.getAllRoots();
	}

	/**
	 * Test 48: Large number of cells
	 */
	@Test
	public void testLargeNumberOfCells() {
		AtomicInteger totalTicks = new AtomicInteger(0);

		CellList cells = new CellList();
		for (int i = 0; i < 100; i++) {
			cells.add(new CountingTemporal(totalTicks));
		}

		cells.tick().get().run();

		Assert.assertEquals(100, totalTicks.get());
	}

	/**
	 * Test 49: Large number of requirements
	 */
	@Test
	public void testLargeNumberOfRequirements() {
		AtomicInteger totalTicks = new AtomicInteger(0);

		CellList cells = new CellList();
		for (int i = 0; i < 50; i++) {
			cells.addRequirement(new CountingTemporal(totalTicks));
		}

		cells.tick().get().run();

		Assert.assertEquals(50, totalTicks.get());
	}

	/**
	 * Test 50: Combined large cells and requirements
	 */
	@Test
	public void testCombinedLargeCellsAndRequirements() {
		AtomicInteger cellTicks = new AtomicInteger(0);
		AtomicInteger reqTicks = new AtomicInteger(0);

		CellList cells = new CellList();

		for (int i = 0; i < 25; i++) {
			cells.add(new CountingTemporal(cellTicks));
		}

		for (int i = 0; i < 25; i++) {
			cells.addRequirement(new CountingTemporal(reqTicks));
		}

		cells.tick().get().run();

		Assert.assertEquals(25, cellTicks.get());
		Assert.assertEquals(25, reqTicks.get());
	}

	/**
	 * Test 51: Repeated tick execution
	 */
	@Test
	public void testRepeatedTickExecution() {
		AtomicInteger tickCount = new AtomicInteger(0);

		CountingTemporal cell = new CountingTemporal(tickCount);

		CellList cells = new CellList();
		cells.add(cell);

		Runnable tick = cells.tick().get();

		for (int i = 0; i < 100; i++) {
			tick.run();
		}

		Assert.assertEquals(100, tickCount.get());
	}

	/**
	 * Test 52: Reset followed by tick
	 */
	@Test
	public void testResetFollowedByTick() {
		AtomicInteger tickCount = new AtomicInteger(0);
		AtomicInteger resetCount = new AtomicInteger(0);

		ResettableCountingTemporal cell = new ResettableCountingTemporal(tickCount, resetCount);

		CellList cells = new CellList();
		cells.add(cell);

		cells.tick().get().run();
		Assert.assertEquals(1, tickCount.get());

		cells.reset();
		Assert.assertEquals(1, resetCount.get());

		cells.tick().get().run();
		Assert.assertEquals(2, tickCount.get());
	}

	// ========== HELPER CLASSES ==========

	private static class TestTemporal implements Cell<PackedCollection>, Temporal {
		private final String name;
		private final List<String> tickOrder;
		private Receptor<PackedCollection> receptor;

		public TestTemporal(String name, List<String> tickOrder) {
			this.name = name;
			this.tickOrder = tickOrder;
		}

		@Override
		public Supplier<Runnable> tick() {
			return () -> () -> tickOrder.add(name);
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> producer) {
			return () -> () -> {};
		}

		@Override
		public void setReceptor(Receptor<PackedCollection> receptor) {
			this.receptor = receptor;
		}

		@Override
		public void reset() {}
	}

	private static class TestSetupTemporal implements Cell<PackedCollection>, Temporal, Setup {
		private final String name;
		private final List<String> setupOrder;
		private Receptor<PackedCollection> receptor;

		public TestSetupTemporal(String name, List<String> setupOrder) {
			this.name = name;
			this.setupOrder = setupOrder;
		}

		@Override
		public Supplier<Runnable> setup() {
			return () -> () -> setupOrder.add(name);
		}

		@Override
		public Supplier<Runnable> tick() {
			return () -> () -> {};
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> producer) {
			return () -> () -> {};
		}

		@Override
		public void setReceptor(Receptor<PackedCollection> receptor) {
			this.receptor = receptor;
		}

		@Override
		public void reset() {}
	}

	private static class TestSetupCell implements Cell<PackedCollection>, Setup {
		private final AtomicInteger setupCount;
		private Receptor<PackedCollection> receptor;

		public TestSetupCell(AtomicInteger setupCount) {
			this.setupCount = setupCount;
		}

		@Override
		public Supplier<Runnable> setup() {
			return () -> () -> setupCount.incrementAndGet();
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> producer) {
			return () -> () -> {};
		}

		@Override
		public void setReceptor(Receptor<PackedCollection> receptor) {
			this.receptor = receptor;
		}

		@Override
		public void reset() {}
	}

	private static class TestResetCell implements Cell<PackedCollection> {
		private final AtomicInteger resetCount;
		private Receptor<PackedCollection> receptor;

		public TestResetCell(AtomicInteger resetCount) {
			this.resetCount = resetCount;
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> producer) {
			return () -> () -> {};
		}

		@Override
		public void setReceptor(Receptor<PackedCollection> receptor) {
			this.receptor = receptor;
		}

		@Override
		public void reset() {
			resetCount.incrementAndGet();
		}
	}

	private static class TestPushReceptor implements Cell<PackedCollection> {
		private final AtomicInteger pushCount;
		private Receptor<PackedCollection> receptor;

		public TestPushReceptor(AtomicInteger pushCount) {
			this.pushCount = pushCount;
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> producer) {
			return () -> () -> pushCount.incrementAndGet();
		}

		@Override
		public void setReceptor(Receptor<PackedCollection> receptor) {
			this.receptor = receptor;
		}

		@Override
		public void reset() {}
	}

	private static class CountingTemporal implements Cell<PackedCollection>, Temporal {
		private final AtomicInteger tickCount;
		private Receptor<PackedCollection> receptor;

		public CountingTemporal(AtomicInteger tickCount) {
			this.tickCount = tickCount;
		}

		@Override
		public Supplier<Runnable> tick() {
			return () -> () -> tickCount.incrementAndGet();
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> producer) {
			return () -> () -> {};
		}

		@Override
		public void setReceptor(Receptor<PackedCollection> receptor) {
			this.receptor = receptor;
		}

		@Override
		public void reset() {}
	}

	private static class SetupCountingTemporal implements Cell<PackedCollection>, Temporal, Setup {
		private final AtomicInteger setupCount;
		private final AtomicInteger tickCount;
		private Receptor<PackedCollection> receptor;

		public SetupCountingTemporal(AtomicInteger setupCount, AtomicInteger tickCount) {
			this.setupCount = setupCount;
			this.tickCount = tickCount;
		}

		@Override
		public Supplier<Runnable> setup() {
			return () -> () -> setupCount.incrementAndGet();
		}

		@Override
		public Supplier<Runnable> tick() {
			return () -> () -> tickCount.incrementAndGet();
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> producer) {
			return () -> () -> {};
		}

		@Override
		public void setReceptor(Receptor<PackedCollection> receptor) {
			this.receptor = receptor;
		}

		@Override
		public void reset() {}
	}

	private static class NonTemporalCell implements Cell<PackedCollection> {
		private final AtomicInteger pushCount;
		private Receptor<PackedCollection> receptor;

		public NonTemporalCell(AtomicInteger pushCount) {
			this.pushCount = pushCount;
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> producer) {
			return () -> () -> pushCount.incrementAndGet();
		}

		@Override
		public void setReceptor(Receptor<PackedCollection> receptor) {
			this.receptor = receptor;
		}

		@Override
		public void reset() {}
	}

	private static class ResettableCountingTemporal implements Cell<PackedCollection>, Temporal {
		private final AtomicInteger tickCount;
		private final AtomicInteger resetCount;
		private Receptor<PackedCollection> receptor;

		public ResettableCountingTemporal(AtomicInteger tickCount, AtomicInteger resetCount) {
			this.tickCount = tickCount;
			this.resetCount = resetCount;
		}

		@Override
		public Supplier<Runnable> tick() {
			return () -> () -> tickCount.incrementAndGet();
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> producer) {
			return () -> () -> {};
		}

		@Override
		public void setReceptor(Receptor<PackedCollection> receptor) {
			this.receptor = receptor;
		}

		@Override
		public void reset() {
			resetCount.incrementAndGet();
		}
	}
}
