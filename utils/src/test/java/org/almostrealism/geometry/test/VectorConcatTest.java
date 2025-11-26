package org.almostrealism.geometry.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Test to isolate the vector concat issue.
 */
public class VectorConcatTest implements TestFeatures, VectorFeatures {

	@Test
	public void simpleVectorFromScalars() {
		log("Creating vector from three scalar values...");

		// Create three scalar value producers (size 1 each, NOT Scalar type which is size 2)
		// Note: scalar(double) now returns c(double) which is size 1
		// Avoid typing as CollectionProducer<Scalar> since Scalar class is size 2 (legacy)
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
			Vector result = new Vector((PackedCollection) vec.get().evaluate(), 0);
			log("Vector result: " + result);

			assertEquals("X should be 1.0", 1.0, result.getX());
			assertEquals("Y should be 2.0", 2.0, result.getY());
			assertEquals("Z should be 3.0", 3.0, result.getZ());
		} catch (Exception e) {
			log("Exception: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}
}
