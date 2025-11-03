package org.almostrealism.geometry.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.geometry.TransformMatrixFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Comprehensive tests for TransformMatrix inversion issues.
 *
 * This test suite demonstrates the critical bug in TransformMatrix.getInverse()
 * where scale matrix inversions produce incorrect results.
 */
public class TransformInversionTest implements TransformMatrixFeatures, TestFeatures {

	@Test
	public void testIdentityMatrixInverse() {
		log("========================================");
		log("TEST: Identity Matrix Inversion");
		log("========================================");

		TransformMatrix identity = new TransformMatrix();
		log("Created identity matrix");
		printMatrix("Identity", identity);

		TransformMatrix inverse = identity.getInverse();
		printMatrix("Inverse", inverse);

		// Inverse of identity should be identity
		assertMatrixEquals("Identity inverse", inverse,
			1, 0, 0, 0,
			0, 1, 0, 0,
			0, 0, 1, 0,
			0, 0, 0, 1);
	}

	@Test
	public void testTranslationMatrixInverse() {
		log("========================================");
		log("TEST: Translation Matrix Inversion");
		log("========================================");

		// Create translation matrix for (3, 2, 1)
		Producer<TransformMatrix> tmProducer = translationMatrix(vector(3.0, 2.0, 1.0));
		TransformMatrix mat = new TransformMatrix(tmProducer.get().evaluate(), 0);

		log("Created translation matrix for (3, 2, 1)");
		printMatrix("Translation", mat);

		TransformMatrix inverse = mat.getInverse();
		printMatrix("Inverse", inverse);

		// Inverse of translation(3,2,1) should be translation(-3,-2,-1)
		assertMatrixEquals("Translation inverse", inverse,
			1, 0, 0, -3,
			0, 1, 0, -2,
			0, 0, 1, -1,
			0, 0, 0, 1);
	}

	@Test
	public void testUniformScaleMatrixInverse() {
		log("========================================");
		log("TEST: Uniform Scale Matrix Inversion");
		log("========================================");

		// Create scale(2,2,2) matrix
		Producer<TransformMatrix> tmProducer = scaleMatrix(vector(2.0, 2.0, 2.0));
		TransformMatrix mat = new TransformMatrix(tmProducer.get().evaluate(), 0);

		log("Created scale(2,2,2) matrix");
		printMatrix("Scale(2,2,2)", mat);

		// Check determinant
		double det = mat.determinant();
		log("Determinant: " + det);
		log("Expected determinant: 8.0 (2*2*2)");
		assertTrue("Determinant should be 8.0", Math.abs(det - 8.0) < 0.001);

		TransformMatrix inverse = mat.getInverse();
		printMatrix("Inverse", inverse);

		// Inverse of scale(2,2,2) should be scale(0.5,0.5,0.5)
		log("Expected: scale(0.5,0.5,0.5)");
		assertMatrixEquals("Scale inverse", inverse,
			0.5, 0,   0,   0,
			0,   0.5, 0,   0,
			0,   0,   0.5, 0,
			0,   0,   0,   1);
	}

	@Test
	public void testNonUniformScaleMatrixInverse() {
		log("========================================");
		log("TEST: Non-uniform Scale Matrix Inversion");
		log("========================================");

		// Create scale(2,3,4) matrix
		Producer<TransformMatrix> tmProducer = scaleMatrix(vector(2.0, 3.0, 4.0));
		TransformMatrix mat = new TransformMatrix(tmProducer.get().evaluate(), 0);

		log("Created scale(2,3,4) matrix");
		printMatrix("Scale(2,3,4)", mat);

		// Check determinant
		double det = mat.determinant();
		log("Determinant: " + det);
		log("Expected determinant: 24.0 (2*3*4)");
		assertTrue("Determinant should be 24.0", Math.abs(det - 24.0) < 0.001);

		TransformMatrix inverse = mat.getInverse();
		printMatrix("Inverse", inverse);

		// Inverse of scale(2,3,4) should be scale(0.5, 0.333, 0.25)
		log("Expected: scale(0.5, 0.333, 0.25)");
		assertMatrixEquals("Non-uniform scale inverse", inverse,
			0.5,  0,     0,    0,
			0,    0.333, 0,    0,
			0,    0,     0.25, 0,
			0,    0,     0,    1);
	}

	@Test
	public void testMatrixMultiplicationVerification() {
		log("========================================");
		log("TEST: Verify M * M^-1 = Identity");
		log("========================================");

		// Create scale(2,2,2) matrix
		Producer<TransformMatrix> tmProducer = scaleMatrix(vector(2.0, 2.0, 2.0));
		TransformMatrix mat = new TransformMatrix(tmProducer.get().evaluate(), 0);
		TransformMatrix inverse = mat.getInverse();

		log("Testing scale(2,2,2) * inverse");
		printMatrix("Original", mat);
		printMatrix("Inverse", inverse);

		// Multiply matrix by its inverse
		TransformMatrix product = mat.multiply(inverse);
		printMatrix("Product (should be identity)", product);

		// Should get identity matrix
		assertMatrixEquals("M * M^-1", product,
			1, 0, 0, 0,
			0, 1, 0, 0,
			0, 0, 1, 0,
			0, 0, 0, 1);
	}

	@Test
	public void testInverseOfInverse() {
		log("========================================");
		log("TEST: (M^-1)^-1 = M");
		log("========================================");

		// Create scale(2,2,2) matrix
		Producer<TransformMatrix> tmProducer = scaleMatrix(vector(2.0, 2.0, 2.0));
		TransformMatrix original = new TransformMatrix(tmProducer.get().evaluate(), 0);

		log("Original scale(2,2,2) matrix:");
		printMatrix("Original", original);

		TransformMatrix inverse = original.getInverse();
		printMatrix("Inverse", inverse);

		TransformMatrix inverseOfInverse = inverse.getInverse();
		printMatrix("Inverse of inverse (should equal original)", inverseOfInverse);

		// Should get back original matrix
		assertMatrixEquals("(M^-1)^-1", inverseOfInverse,
			2, 0, 0, 0,
			0, 2, 0, 0,
			0, 0, 2, 0,
			0, 0, 0, 1);
	}

	@Test
	public void testSmallScaleInverse() {
		log("========================================");
		log("TEST: Small Scale Factor Inversion");
		log("========================================");

		// Create scale(0.5,0.5,0.5) matrix
		Producer<TransformMatrix> tmProducer = scaleMatrix(vector(0.5, 0.5, 0.5));
		TransformMatrix mat = new TransformMatrix(tmProducer.get().evaluate(), 0);

		log("Created scale(0.5,0.5,0.5) matrix");
		printMatrix("Scale(0.5,0.5,0.5)", mat);

		// Check determinant
		double det = mat.determinant();
		log("Determinant: " + det);
		log("Expected determinant: 0.125 (0.5*0.5*0.5)");
		assertTrue("Determinant should be 0.125", Math.abs(det - 0.125) < 0.001);

		TransformMatrix inverse = mat.getInverse();
		printMatrix("Inverse", inverse);

		// Inverse of scale(0.5,0.5,0.5) should be scale(2,2,2)
		log("Expected: scale(2,2,2)");
		assertMatrixEquals("Small scale inverse", inverse,
			2, 0, 0, 0,
			0, 2, 0, 0,
			0, 0, 2, 0,
			0, 0, 0, 1);
	}

	@Test
	public void testDebugAdjointCalculation() {
		log("========================================");
		log("TEST: Debug Adjoint Calculation");
		log("========================================");

		// Create simple scale(2,2,2) matrix
		Producer<TransformMatrix> tmProducer = scaleMatrix(vector(2.0, 2.0, 2.0));
		TransformMatrix mat = new TransformMatrix(tmProducer.get().evaluate(), 0);

		log("Testing adjoint calculation for scale(2,2,2)");
		printMatrix("Original", mat);

		// Get adjoint
		TransformMatrix adjoint = mat.adjoint();
		printMatrix("Adjoint", adjoint);

		// For a diagonal matrix, adjoint should be:
		// Each diagonal element = product of other diagonal elements
		// For scale(2,2,2): adjoint diagonal should be (4, 4, 4, 2)
		// because adjoint[0,0] = 2*2*1 = 4, adjoint[1,1] = 2*2*1 = 4, etc.
		log("Expected adjoint diagonal: [4, 4, 4, 2]");

		double a00 = adjoint.toArray()[0];
		double a11 = adjoint.toArray()[5];
		double a22 = adjoint.toArray()[10];
		double a33 = adjoint.toArray()[15];

		log("Actual adjoint diagonal: [" + a00 + ", " + a11 + ", " + a22 + ", " + a33 + "]");

		// Now compute inverse: adjoint / determinant
		double det = mat.determinant();
		log("Determinant: " + det);

		log("Inverse calculation: adjoint / " + det);
		log("Expected diagonal after division: [" + (a00/det) + ", " + (a11/det) + ", " + (a22/det) + ", " + (a33/det) + "]");
	}

	// Helper methods

	private void printMatrix(String label, TransformMatrix mat) {
		double[] data = mat.toArray();
		log(label + " matrix:");
		for (int i = 0; i < 4; i++) {
			log(String.format("  [%7.3f, %7.3f, %7.3f, %7.3f]",
				data[i*4], data[i*4+1], data[i*4+2], data[i*4+3]));
		}
	}

	private void assertMatrixEquals(String message, TransformMatrix actual,
		double m00, double m01, double m02, double m03,
		double m10, double m11, double m12, double m13,
		double m20, double m21, double m22, double m23,
		double m30, double m31, double m32, double m33) {

		double[] data = actual.toArray();
		double[] expected = {
			m00, m01, m02, m03,
			m10, m11, m12, m13,
			m20, m21, m22, m23,
			m30, m31, m32, m33
		};

		for (int i = 0; i < 16; i++) {
			int row = i / 4;
			int col = i % 4;
			assertTrue(
				message + " - M[" + row + "," + col + "] should be " + expected[i] + " but was " + data[i],
				Math.abs(data[i] - expected[i]) < 0.01
			);
		}
	}
}