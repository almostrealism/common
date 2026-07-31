package io.almostrealism.db.test;

import io.almostrealism.GraphPersist;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for GraphPersist database store and retrieve operations.
 */
public class DatabaseTest extends TestSuiteBase {
	/**
	 * Tests storing and retrieving a tensor of values.
	 */
	@Test(timeout = 10000)
	public void storeAndRetrieve() {
		Tensor<PackedCollection> t = new Tensor<>();
		t.insert(pack(1.0), 0, 0);
		t.insert(pack(2.0), 0, 1);
		t.insert(pack(3.0), 0, 2);
		t.insert(pack(4.0), 1, 0);
		t.insert(pack(5.0), 1, 1);
		t.insert(pack(6.0), 1, 2);

		GraphPersist.local().save("/test", t.pack());

		PackedCollection r = GraphPersist.local().read("/test", new TraversalPolicy(2, 3, 1));
		assertEquals("Value at index 2", 3.0, r.toDouble(2));
		assertEquals("Value at index 4", 5.0, r.toDouble(4));
	}
}
