/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.util;

import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;

public interface TestFeatures extends CodeFeatures, HardwareFeatures, TestSettings {

	default void assertEquals(Scalar a, Scalar b) {
		assertEquals(a.getValue(), b.getValue());
	}

	default void assertEquals(double a, Scalar b) {
		assertEquals(a, b.getValue());
	}

	default void assertEquals(double a, double b) {
		assertEquals(a, b, true);
	}

	default void assertNotEquals(Scalar a, Scalar b) {
		assertNotEquals(a.getValue(), b.getValue());
	}

	default void assertNotEquals(double a, Scalar b) {
		assertNotEquals(a, b.getValue());
	}

	default void assertNotEquals(double a, double b) {
		assertEquals(a, b, false);
	}

	private static void assertEquals(double a, double b, boolean positive) {
		double gap = Hardware.getLocalHardware().isDoublePrecision() ? Math.pow(10, -10) : Math.pow(10, -6);

		if (Math.abs(a - b) >= gap) {
			if (positive) {
				System.err.println("TestFeatures: " + b + " != " + a);
				throw new AssertionError();
			}
		} else if (!positive) {
			System.err.println("TestFeatures: " + b + " == " + a);
			throw new AssertionError();
		}
	}
}
