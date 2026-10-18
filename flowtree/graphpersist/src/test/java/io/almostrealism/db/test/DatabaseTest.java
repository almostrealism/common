package io.almostrealism.db.test;

import io.almostrealism.GraphPersist;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.db.DatabasePools;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for GraphPersist database store and retrieve operations.
 */
public class DatabaseTest extends TestSuiteBase {

	/**
	 * {@link DatabasePools#close(String)} closes and removes a registered pool,
	 * reporting {@code true}; a subsequent {@link DatabasePools#get(String)}
	 * returns {@code null} and a repeat close reports {@code false}. A close of a
	 * URL that was never registered also reports {@code false}. The c3p0 pool is
	 * created lazily, so no real database connection is opened.
	 */
	@Test(timeout = 10000)
	public void closeRemovesRegisteredPool() {
		String url = "jdbc:mysql://localhost/db_pools_close_test";

		assertFalse("close of an unregistered URL reports false", DatabasePools.close(url));

		DatabasePools.open(url, "user", "secret");
		assertNotNull("pool should be registered after open", DatabasePools.get(url));

		assertTrue("close of a registered pool reports true", DatabasePools.close(url));
		assertNull("pool should be removed after close", DatabasePools.get(url));
		assertFalse("repeat close reports false", DatabasePools.close(url));
	}
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
