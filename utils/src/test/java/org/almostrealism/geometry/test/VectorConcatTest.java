package org.almostrealism.geometry.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test to isolate the vector concat issue.
 */
public class VectorConcatTest extends TestSuiteBase implements VectorFeatures {

	@Test(timeout = 10000)
	public void simpleVectorFromScalars() {
		log("Creating vector from three scalar values...");

		// Create three scalar value producers (size 1 each, NOT Scalar type which is size 2)
		// Note: scalar(double) now returns c(double) which is size 1
		// Note: CollectionProducer is no longer generic
		var x = c(1.0);
		var y = c(2.0);
		var z = c(3.0);

		log("Scalar values created");
		log("X shape: " + shape(x));
		log("Y shape: " + shape(y));
		log("Z shape: " + shape(z));

		// Try to create a vector from them
		try {
			CollectionProducer vec = vector(x, y, z);
			log("Vector created: " + vec);

			// Vector is now a view over PackedCollection
			Vector result = new Vector(vec.get().evaluate(), 0);
			log("Vector result: " + result);

			assertEquals("X should be 1.0", 1.0, result.toDouble(0));
			assertEquals("Y should be 2.0", 2.0, result.toDouble(1));
			assertEquals("Z should be 3.0", 3.0, result.toDouble(2));
		} catch (Exception e) {
			log("Exception: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}
}
