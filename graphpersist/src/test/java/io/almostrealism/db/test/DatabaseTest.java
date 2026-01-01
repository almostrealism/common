package io.almostrealism.db.test;

import io.almostrealism.GraphPersist;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class DatabaseTest implements TestFeatures {
	private static PackedCollection scalarValue(double value) {
		PackedCollection s = new PackedCollection(1);
		s.setMem(0, value);
		return s;
	}

	@Test
	public void storeAndRetrieve() {
		Tensor<PackedCollection> t = new Tensor<>();
		t.insert(scalarValue(1), 0, 0);
		t.insert(scalarValue(2), 0, 1);
		t.insert(scalarValue(3), 0, 2);
		t.insert(scalarValue(4), 1, 0);
		t.insert(scalarValue(5), 1, 1);
		t.insert(scalarValue(6), 1, 2);

		GraphPersist.local().save("/test", t.pack());

		PackedCollection r = GraphPersist.local().read("/test", new TraversalPolicy(2, 3, 1));
		assertEquals("Value at index 2", 3.0, r.toDouble(2));
		assertEquals("Value at index 4", 5.0, r.toDouble(4));
	}
}
