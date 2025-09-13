/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.junit.Test;

import java.util.function.Supplier;

public class CodeFeaturesTests implements TestFeatures {
	@Test
	public void partialComputation1() {
		Producer<PackedCollection<?>> p = multiply(c(1.0), c(2.0));
		Producer<PackedCollection<?>> q = add(c(5.0), p);

		Evaluable<PackedCollection<?>> pev = p.get();
		Evaluable<PackedCollection<?>> qev = q.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
	}

	@Test
	public void partialComputation2() {
		Producer<PackedCollection<?>> p = multiply(c(1.0), c(2.0));
		Producer<PackedCollection<?>> q = add(c(5.0), p);

		Evaluable<PackedCollection<?>> pev = p.get();
		Evaluable<PackedCollection<?>> qev = q.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
	}

	@Test
	public void partialComputation3() {
		Scalar multiplier = new Scalar(1.0);
		Producer<PackedCollection<?>> p = multiply(p(multiplier), c(2.0));
		Producer<PackedCollection<?>> q = add(c(5.0), p);

		Evaluable<PackedCollection<?>> pev = p.get();
		Evaluable<PackedCollection<?>> qev = q.get();

		assertEquals(2.0, new Scalar(pev.evaluate()));
		assertEquals(7.0, new Scalar(qev.evaluate()));

		multiplier.setValue(2.0);
		assertEquals(4.0, new Scalar(pev.evaluate()));
		assertEquals(9.0, new Scalar(qev.evaluate()));
	}

	@Test
	public void partialComputation4() {
		Scalar multiplier = new Scalar(1.0);
		Producer<PackedCollection<?>> p = multiply(p(multiplier), c(2.0));
		Producer<PackedCollection<?>> q = add(c(5.0), p);

		Evaluable<PackedCollection<?>> qev = q.get();
		assertEquals(7.0, new Scalar(qev.evaluate()));

		Evaluable<PackedCollection<?>> pev = p.get();
		assertEquals(2.0, new Scalar(pev.evaluate()));

		// Make sure the process respects the update to a provided value
		multiplier.setValue(2.0);
		assertEquals(4.0, new Scalar(p.get().evaluate()));
		assertEquals(9.0, new Scalar(q.get().evaluate()));

		// Make sure the original Evaluables are not affected
		assertEquals(4.0, new Scalar(pev.evaluate()));
		assertEquals(9.0, new Scalar(qev.evaluate()));
	}

	@Test
	public void partialComputation5() {
		Producer<PackedCollection<?>> p = multiply(c(1.0), c(2.0));
		Producer<PackedCollection<?>> q = add(c(5.0), p);
		Producer<PackedCollection<?>> r = multiply(c(5.0), p);

		Evaluable<PackedCollection<?>> pev = p.get();
		Evaluable<PackedCollection<?>> qev = q.get();
		Evaluable<PackedCollection<?>> rev = r.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
		assertEquals(10.0, rev.evaluate());
	}

	@Test
	public void partialComputation6() {
		Scalar multiplier = new Scalar(1.0);
		Producer<PackedCollection<?>> p = multiply(p(multiplier), c(2.0));
		Producer<PackedCollection<?>> q = add(c(5.0), p);
		Producer<PackedCollection<?>> r = multiply(c(5.0), p);

		assertEquals(10.0, new Scalar(r.get().evaluate()));
		assertEquals(2.0, new Scalar(p.get().evaluate()));
		assertEquals(7.0, new Scalar(q.get().evaluate()));
	}

	@Test
	public void partialComputation7() {
		Scalar multiplier = new Scalar(1.0);
		Producer<PackedCollection<?>> p = multiply(() -> args -> multiplier, c(2.0));
		Producer<PackedCollection<?>> q = add(c(5.0), p);
		Producer<PackedCollection<?>> r = multiply(c(5.0), p);

		assertEquals(10.0, new Scalar(r.get().evaluate()));
		assertEquals(2.0, new Scalar(p.get().evaluate()));
		assertEquals(7.0, new Scalar(q.get().evaluate()));

		multiplier.setValue(2.0);
		assertEquals(20.0, new Scalar(r.get().evaluate()));
		assertEquals(9.0, new Scalar(q.get().evaluate()));
		assertEquals(4.0, new Scalar(p.get().evaluate()));
	}

	@Test
	public void addToProvider() {
		Scalar value = new Scalar(1.0);
		Producer<Scalar> s = add(scalar(1), p(value));
		value.setValue(2);

		Evaluable<Scalar> ev = s.get();
		PackedCollection out = ev.evaluate();
		assertEquals(3.0, out.toDouble(0));

		value.setValue(3);
		out = ev.evaluate();
		assertEquals(4.0, out.toDouble(0));
	}

	@Test
	public void addToProviderAndAssign() {
		Scalar value = new Scalar(1.0);
		Scalar dest = new Scalar(0.0);
		Supplier<Runnable> s = a(1, p(dest), add(scalar(1), p(value)).divide(scalar(2.0)));
		value.setValue(2);

		Runnable r = s.get();
		r.run();
		System.out.println(dest.getValue());
		assertEquals(1.5, dest.getValue());

		value.setValue(3);
		r.run();
		assertEquals(2.0, dest.getValue());
	}

	@Test
	public void loop1() {
		Scalar value = new Scalar(1.0);
		Scalar dest = new Scalar(0.0);
		Supplier<Runnable> s = lp(a(1, p(dest), add(scalar(1), p(value)).divide(scalar(2.0))), 2);
		value.setValue(2);

		Runnable r = s.get();
		r.run();
		System.out.println(dest.getValue());
		assertEquals(1.5, dest.getValue());

		value.setValue(3);
		r.run();
		assertEquals(2.0, dest.getValue());
	}

	@Test
	public void loop2() {
		Scalar dest = new Scalar(0.0);
		Supplier<Runnable> s = lp(a(1, p(dest), add(scalar(1.0), p(dest))), 3);
		Runnable r = s.get();

		r.run();
		System.out.println(dest.getValue());
		assertEquals(3.0, dest);

		r.run();
		System.out.println(dest.getValue());
		assertEquals(6.0, dest);
	}
}
