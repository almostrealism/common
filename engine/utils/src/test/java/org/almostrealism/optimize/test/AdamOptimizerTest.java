/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.optimize.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.optimize.AdamOptimizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link AdamOptimizer}: two update steps with a changing gradient match the
 * closed-form Adam update with bias correction.
 */
public class AdamOptimizerTest extends TestSuiteBase {
	/** Two steps of Adam match a reference computed at the test boundary. */
	@Test(timeout = 60000)
	public void twoStepsMatchReference() {
		double lr = 0.01;
		double beta1 = 0.9;
		double beta2 = 0.999;
		double eps = 1e-7;
		PackedCollection weights = PackedCollection.of(0.5, -1.0, 2.0);
		PackedCollection[] gradients = {
				PackedCollection.of(0.2, -0.4, 1.0),
				PackedCollection.of(-0.1, 0.3, 0.5)
		};

		PackedCollection gradient = new PackedCollection(shape(3));
		Runnable step = new AdamOptimizer(lr, beta1, beta2)
				.apply("test", cp(weights), cp(gradient)).get();

		double[] w = weights.toArray();
		double[] m = new double[3];
		double[] v = new double[3];
		for (int t = 1; t <= gradients.length; t++) {
			gradient.setFrom(0, gradients[t - 1]);
			step.run();

			double[] g = gradients[t - 1].toArray();
			for (int i = 0; i < 3; i++) {
				m[i] = beta1 * m[i] + (1 - beta1) * g[i];
				v[i] = beta2 * v[i] + (1 - beta2) * g[i] * g[i];
				double mt = m[i] / (1 - Math.pow(beta1, t));
				double vt = v[i] / (1 - Math.pow(beta2, t));
				w[i] -= lr * mt / (Math.sqrt(vt) + eps);
				Assert.assertEquals("step " + t + " weight " + i, w[i], weights.toDouble(i), 1e-5);
			}
		}
	}
}
