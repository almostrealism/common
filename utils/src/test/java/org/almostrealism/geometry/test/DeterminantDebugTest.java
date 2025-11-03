/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.geometry.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.geometry.TransformMatrixFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Focused test to debug TransformMatrixDeterminant returning 1.0 for all matrices.
 */
public class DeterminantDebugTest implements TransformMatrixFeatures, TestFeatures {

	@Test
	public void testSimpleDeterminant() {
		log("========================================");
		log("TEST: Simple Determinant Calculation");
		log("========================================");

		// Test identity matrix (det should be 1)
		TransformMatrix identity = new TransformMatrix();
		double identityDet = identity.determinant();
		log("Identity matrix determinant: " + identityDet);
		log("Expected: 1.0");
		assertTrue("Identity determinant should be 1.0", Math.abs(identityDet - 1.0) < 0.001);

		// Test scale(2,2,2) matrix (det should be 8)
		Producer<TransformMatrix> scale2Producer = scaleMatrix(vector(2.0, 2.0, 2.0));
		TransformMatrix scale2 = new TransformMatrix(scale2Producer.get().evaluate(), 0);
		printMatrix("Scale(2,2,2)", scale2);
		double scale2Det = scale2.determinant();
		log("Scale(2,2,2) determinant: " + scale2Det);
		log("Expected: 8.0");
		assertTrue("Scale(2,2,2) determinant should be 8.0", Math.abs(scale2Det - 8.0) < 0.001);

		// Test scale(3,3,3) matrix (det should be 27)
		Producer<TransformMatrix> scale3Producer = scaleMatrix(vector(3.0, 3.0, 3.0));
		TransformMatrix scale3 = new TransformMatrix(scale3Producer.get().evaluate(), 0);
		double scale3Det = scale3.determinant();
		log("Scale(3,3,3) determinant: " + scale3Det);
		log("Expected: 27.0");
		assertTrue("Scale(3,3,3) determinant should be 27.0", Math.abs(scale3Det - 27.0) < 0.001);
	}

	@Test
	public void testNonDiagonalMatrix() {
		log("========================================");
		log("TEST: Non-Diagonal Matrix Determinant");
		log("========================================");

		// Create a simple non-diagonal matrix with known determinant
		double[][] matrixData = new double[][] {
			{2, 1, 0, 0},
			{1, 2, 0, 0},
			{0, 0, 1, 0},
			{0, 0, 0, 1}
		};
		TransformMatrix mat = new TransformMatrix(matrixData);
		printMatrix("Test matrix", mat);

		double det = mat.determinant();
		log("Determinant: " + det);

		// For this matrix: det = (2*2 - 1*1) * 1 * 1 = 3
		log("Expected: 3.0");
		assertTrue("Determinant should be 3.0", Math.abs(det - 3.0) < 0.001);
	}

	@Test
	public void testUpperTriangularConversion() {
		log("========================================");
		log("TEST: Debug Upper Triangular Conversion");
		log("========================================");

		// For a diagonal matrix, upper triangular conversion should be a no-op
		Producer<TransformMatrix> scaleProducer = scaleMatrix(vector(2.0, 2.0, 2.0));
		TransformMatrix scale = new TransformMatrix(scaleProducer.get().evaluate(), 0);

		log("Original matrix:");
		printMatrix("Scale(2,2,2)", scale);

		// The TransformMatrixDeterminant should:
		// 1. Convert to upper triangular (no-op for diagonal)
		// 2. Multiply diagonal elements: 2*2*2*1 = 8

		double det = scale.determinant();
		log("Determinant after upper triangular process: " + det);
		log("Should be: 8.0");

		// Let's also check with a different scale
		TransformMatrix scale5 = new TransformMatrix(
			scaleMatrix(vector(5.0, 5.0, 5.0)).get().evaluate(), 0);
		double det5 = scale5.determinant();
		log("Scale(5,5,5) determinant: " + det5);
		log("Should be: 125.0");

		assertFalse("Determinants should not all be 1.0",
			Math.abs(det - 1.0) < 0.001 && Math.abs(det5 - 1.0) < 0.001);
	}

	private void printMatrix(String label, TransformMatrix mat) {
		double[] data = mat.toArray();
		log(label + " matrix:");
		for (int i = 0; i < 4; i++) {
			log(String.format("  [%7.3f, %7.3f, %7.3f, %7.3f]",
				data[i*4], data[i*4+1], data[i*4+2], data[i*4+3]));
		}
	}
}