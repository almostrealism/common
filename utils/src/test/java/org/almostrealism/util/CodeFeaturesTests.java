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
import org.almostrealism.collect.PackedCollection;
import org.junit.Test;

import java.util.function.Supplier;

public class CodeFeaturesTests implements TestFeatures {
	@Test
	public void partialComputation1() {
		Producer<PackedCollection> p = multiply(c(1.0), c(2.0));
		Producer<PackedCollection> q = add(c(5.0), p);

		Evaluable<PackedCollection> pev = p.get();
		Evaluable<PackedCollection> qev = q.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
	}

	@Test
	public void partialComputation2() {
		Producer<PackedCollection> p = multiply(c(1.0), c(2.0));
		Producer<PackedCollection> q = add(c(5.0), p);

		Evaluable<PackedCollection> pev = p.get();
		Evaluable<PackedCollection> qev = q.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
	}

	@Test
	public void partialComputation3() {
		PackedCollection multiplier = pack(1.0);
		Producer<PackedCollection> p = multiply(p(multiplier), c(2.0));
		Producer<PackedCollection> q = add(c(5.0), p);

		Evaluable<PackedCollection> pev = p.get();
		Evaluable<PackedCollection> qev = q.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());

		multiplier.setMem(0, 2.0);
		assertEquals(4.0, pev.evaluate());
		assertEquals(9.0, qev.evaluate());
	}

	@Test
	public void partialComputation4() {
		PackedCollection multiplier = pack(1.0);
		Producer<PackedCollection> p = multiply(p(multiplier), c(2.0));
		Producer<PackedCollection> q = add(c(5.0), p);

		Evaluable<PackedCollection> qev = q.get();
		assertEquals(7.0, qev.evaluate());

		Evaluable<PackedCollection> pev = p.get();
		assertEquals(2.0, pev.evaluate());

		// Make sure the process respects the update to a provided value
		multiplier.setMem(0, 2.0);
		assertEquals(4.0, p.get().evaluate());
		assertEquals(9.0, q.get().evaluate());

		// Make sure the original Evaluables are not affected
		assertEquals(4.0, pev.evaluate());
		assertEquals(9.0, qev.evaluate());
	}

	@Test
	public void partialComputation5() {
		Producer<PackedCollection> p = multiply(c(1.0), c(2.0));
		Producer<PackedCollection> q = add(c(5.0), p);
		Producer<PackedCollection> r = multiply(c(5.0), p);

		Evaluable<PackedCollection> pev = p.get();
		Evaluable<PackedCollection> qev = q.get();
		Evaluable<PackedCollection> rev = r.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
		assertEquals(10.0, rev.evaluate());
	}

	@Test
	public void partialComputation6() {
		PackedCollection multiplier = pack(1.0);
		Producer<PackedCollection> p = multiply(p(multiplier), c(2.0));
		Producer<PackedCollection> q = add(c(5.0), p);
		Producer<PackedCollection> r = multiply(c(5.0), p);

		assertEquals(10.0, r.get().evaluate());
		assertEquals(2.0, p.get().evaluate());
		assertEquals(7.0, q.get().evaluate());
	}

	@Test
	public void partialComputation7() {
		PackedCollection multiplier = pack(1.0);
		Producer<PackedCollection> p = multiply(func(shape(1), args -> multiplier), c(2.0));
		Producer<PackedCollection> q = add(c(5.0), p);
		Producer<PackedCollection> r = multiply(c(5.0), p);

		assertEquals(10.0, r.get().evaluate());
		assertEquals(2.0, p.get().evaluate());
		assertEquals(7.0, q.get().evaluate());

		multiplier.setMem(0, 2.0);
		assertEquals(20.0, r.get().evaluate());
		assertEquals(9.0, q.get().evaluate());
		assertEquals(4.0, p.get().evaluate());
	}

	@Test
	public void addToProvider() {
		PackedCollection value = pack(1.0);
		Producer<PackedCollection> s = add(c(1), p(value));
		value.setMem(0, 2.0);

		Evaluable<PackedCollection> ev = s.get();
		PackedCollection out = ev.evaluate();
		assertEquals(3.0, out.toDouble(0));

		value.setMem(0, 3.0);
		out = ev.evaluate();
		assertEquals(4.0, out.toDouble(0));
	}

	@Test
	public void addToProviderAndAssign() {
		PackedCollection value = pack(1.0);
		PackedCollection dest = pack(0.0);
		Supplier<Runnable> s = a(1, p(dest), add(c(1), p(value)).divide(c(2.0)));
		value.setMem(0, 2.0);

		Runnable r = s.get();
		r.run();
		System.out.println(dest.toDouble(0));
		assertEquals(1.5, dest.toDouble(0));

		value.setMem(0, 3.0);
		r.run();
		assertEquals(2.0, dest.toDouble(0));
	}

	@Test
	public void loop1() {
		PackedCollection value = pack(1.0);
		PackedCollection dest = pack(0.0);
		Supplier<Runnable> s = lp(a(1, p(dest), add(c(1), p(value)).divide(c(2.0))), 2);
		value.setMem(0, 2.0);

		Runnable r = s.get();
		r.run();
		System.out.println(dest.toDouble(0));
		assertEquals(1.5, dest.toDouble(0));

		value.setMem(0, 3.0);
		r.run();
		assertEquals(2.0, dest.toDouble(0));
	}

	@Test
	public void loop2() {
		PackedCollection dest = pack(0.0);
		Supplier<Runnable> s = lp(a(1, p(dest), add(c(1.0), p(dest))), 3);
		Runnable r = s.get();

		r.run();
		System.out.println(dest.toDouble(0));
		assertEquals(3.0, dest);

		r.run();
		System.out.println(dest.toDouble(0));
		assertEquals(6.0, dest);
	}
}
