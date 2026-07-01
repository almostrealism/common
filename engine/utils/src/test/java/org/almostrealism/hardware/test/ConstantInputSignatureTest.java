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

package org.almostrealism.hardware.test;

import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Signature;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Objects;

/**
 * Directly answers the question: do two structurally-identical computations built over
 * <em>different</em> constant {@link PackedCollection} inputs of the same shape
 * (e.g. {@code cp(a).add(cp(b))} versus {@code cp(c).add(cp(d))}) produce the
 * <em>same</em> computation signature — the key the JVM-wide instruction cache uses for kernel
 * reuse — or different signatures?
 */
public class ConstantInputSignatureTest extends TestSuiteBase {

	/**
	 * Builds {@code cp(a).add(cp(b))} and {@code cp(c).add(cp(d))} with distinct same-shape
	 * constant collections and logs both signatures and whether they are equal.
	 */
	@Test(timeout = 60000)
	public void differentConstantsSameShape() {
		int n = 6;

		PackedCollection a = new PackedCollection(shape(n)).randFill().traverseEach();
		PackedCollection b = new PackedCollection(shape(n)).randFill().traverseEach();
		PackedCollection c = new PackedCollection(shape(n)).randFill().traverseEach();
		PackedCollection d = new PackedCollection(shape(n)).randFill().traverseEach();

		// cp(a).add(cp(b)) and cp(c).add(cp(d)) — equivalently via the static add(...) form.
		Producer<PackedCollection> sum1 = add(cp(a), cp(b));
		Producer<PackedCollection> sum2 = add(cp(c), cp(d));

		String sig1 = Signature.of(sum1);
		String sig2 = Signature.of(sum2);

		log("sig1=" + sig1);
		log("sig2=" + sig2);
		log("equalSignatures=" + Objects.equals(sig1, sig2));

		// Also compare against a structurally DIFFERENT computation (subtract) as a control: it
		// must differ from the add signatures, or the signature carries no structure at all.
		Producer<PackedCollection> diff = subtract(cp(a), cp(b));
		String sigDiff = Signature.of(diff);
		log("sigDiff(subtract)=" + sigDiff);
		log("addDiffersFromSubtract=" + !Objects.equals(sig1, sigDiff));

		Assert.assertNotNull("add(cp,cp) produced a null signature (uncacheable)", sig1);
		Assert.assertNotNull("second add(cp,cp) produced a null signature (uncacheable)", sig2);

		// Guard the structural-signature contract documented on Signature: structurally identical
		// computations over different same-shape constant inputs must share a signature so the
		// JVM-wide instruction cache can reuse one compiled kernel, and a structurally different
		// computation must not collide with them.
		Assert.assertEquals("add(cp(a),cp(b)) and add(cp(c),cp(d)) over different same-shape "
				+ "constants must produce equal signatures", sig1, sig2);
		Assert.assertNotEquals("add and subtract must produce different signatures", sig1, sigDiff);
	}
}
