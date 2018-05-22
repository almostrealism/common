/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.physics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

public class Orbital implements Comparable<Orbital>, PhysicalConstants {
	private static TreeSet<Orbital> all = new TreeSet<>();

	static {
		all.addAll(Arrays.asList(s1(), s2(), s3(), s4(), s5(), s6(), s7()));
		all.addAll(Arrays.asList(p2x(), p2y(), p2z()));
		all.addAll(Arrays.asList(p3x(), p3y(), p3z()));
		all.addAll(Arrays.asList(p4x(), p4y(), p4z()));
		all.addAll(Arrays.asList(p5x(), p5y(), p5z()));
		all.addAll(Arrays.asList(p6x(), p6y(), p6z()));
		all.addAll(Arrays.asList(p7x(), p7y(), p7z()));
		all.addAll(Arrays.asList(d3a(), d3b(), d3c(), d3d(), d3e()));
		all.addAll(Arrays.asList(d4a(), d4b(), d4c(), d4d(), d4e()));
		all.addAll(Arrays.asList(d5a(), d5b(), d5c(), d5d(), d5e()));
		all.addAll(Arrays.asList(d6a(), d6b(), d6c(), d6d(), d6e()));
		all.addAll(Arrays.asList(d7a(), d7b(), d7c(), d7d(), d7e()));
		all.addAll(Arrays.asList(f4a(), f4b(), f4c(), f4d(), f4e(), f4f(), f4g()));
		all.addAll(Arrays.asList(f5a(), f5b(), f5c(), f5d(), f5e(), f5f(), f5g()));
		all.addAll(Arrays.asList(f6a(), f6b(), f6c(), f6d(), f6e(), f6f(), f6g()));
		all.addAll(Arrays.asList(f7a(), f7b(), f7c(), f7d(), f7e(), f7f(), f7g()));
	}

	private int principal, angular, magnetic;
	
	public Orbital(int principal, int angular, int magnetic) {
		this.principal = principal;
		this.angular = angular;
		this.magnetic = magnetic;
	}
	
	public int getPrincipal() { return principal; }
	public int getAngular() { return angular; }
	public int getMagnetic() { return magnetic; }
	
	public SubShell populate(int electrons) { return new SubShell(this, electrons); }

	public double getEnergy(int protons) {
		return HCR * protons * protons * principal * principal;
	}

	protected List<Orbital> getHigherOrbitals() {
		double energy = this.getEnergy(1);
		List<Orbital> l = new ArrayList<>();

		for (Orbital o : all) {
			if (o.getEnergy(1) > energy) {
				l.add(o);
			}
		}

		return l;
	}

	public boolean equals(Object o) {
		if (o instanceof Orbital == false) return false;
		Orbital or = (Orbital) o;
		return principal == or.principal && angular == or.angular && magnetic == or.magnetic;
	}
	
	public int hashCode() { return principal; }

	@Override
	public int compareTo(Orbital o) {
		return (int) (10000 * (this.getEnergy(1) - o.getEnergy(1)));
	}
	
	public static Orbital s1() { return new Orbital(1, 0, 0); }
	public static Orbital s2() { return new Orbital(2, 0, 0); }
	public static Orbital s3() { return new Orbital(3, 0, 0); }
	public static Orbital s4() { return new Orbital(4, 0, 0); }
	public static Orbital s5() { return new Orbital(5, 0, 0); }
	public static Orbital s6() { return new Orbital(6, 0, 0); }
	public static Orbital s7() { return new Orbital(7, 0, 0); }
	
	public static Orbital p2x() { return new Orbital(2, 1, -1); }
	public static Orbital p2y() { return new Orbital(2, 1, 0); }
	public static Orbital p2z() { return new Orbital(2, 1, 1); }
	
	public static Orbital p3x() { return new Orbital(3, 1, -1); }
	public static Orbital p3y() { return new Orbital(3, 1, 0); }
	public static Orbital p3z() { return new Orbital(3, 1, 1); }
	
	public static Orbital p4x() { return new Orbital(4, 1, -1); }
	public static Orbital p4y() { return new Orbital(4, 1, 0); }
	public static Orbital p4z() { return new Orbital(4, 1, 1); }
	
	public static Orbital p5x() { return new Orbital(5, 1, -1); }
	public static Orbital p5y() { return new Orbital(5, 1, 0); }
	public static Orbital p5z() { return new Orbital(5, 1, 1); }
	
	public static Orbital p6x() { return new Orbital(6, 1, -1); }
	public static Orbital p6y() { return new Orbital(6, 1, 0); }
	public static Orbital p6z() { return new Orbital(6, 1, 1); }
	
	public static Orbital p7x() { return new Orbital(7, 1, -1); }
	public static Orbital p7y() { return new Orbital(7, 1, 0); }
	public static Orbital p7z() { return new Orbital(7, 1, 1); }
	
	public static Orbital d3a() { return new Orbital(3, 2, -2); }
	public static Orbital d3b() { return new Orbital(3, 2, -1); }
	public static Orbital d3c() { return new Orbital(3, 2, 0); }
	public static Orbital d3d() { return new Orbital(3, 2, 1); }
	public static Orbital d3e() { return new Orbital(3, 2, 2); }
	
	public static Orbital d4a() { return new Orbital(4, 2, -2); }
	public static Orbital d4b() { return new Orbital(4, 2, -1); }
	public static Orbital d4c() { return new Orbital(4, 2, 0); }
	public static Orbital d4d() { return new Orbital(4, 2, 1); }
	public static Orbital d4e() { return new Orbital(4, 2, 2); }

	public static Orbital d5a() { return new Orbital(5, 2, -2); }
	public static Orbital d5b() { return new Orbital(5, 2, -1); }
	public static Orbital d5c() { return new Orbital(5, 2, 0); }
	public static Orbital d5d() { return new Orbital(5, 2, 1); }
	public static Orbital d5e() { return new Orbital(5, 2, 2); }
	
	public static Orbital d6a() { return new Orbital(6, 2, -2); }
	public static Orbital d6b() { return new Orbital(6, 2, -1); }
	public static Orbital d6c() { return new Orbital(6, 2, 0); }
	public static Orbital d6d() { return new Orbital(6, 2, 1); }
	public static Orbital d6e() { return new Orbital(6, 2, 2); }
	
	public static Orbital d7a() { return new Orbital(7, 2, -2); }
	public static Orbital d7b() { return new Orbital(7, 2, -1); }
	public static Orbital d7c() { return new Orbital(7, 2, 0); }
	public static Orbital d7d() { return new Orbital(7, 2, 1); }
	public static Orbital d7e() { return new Orbital(7, 2, 2); }
	
	public static Orbital f4a() { return new Orbital(4, 3, -3); }
	public static Orbital f4b() { return new Orbital(4, 3, -2); }
	public static Orbital f4c() { return new Orbital(4, 3, -1); }
	public static Orbital f4d() { return new Orbital(4, 3, 0); }
	public static Orbital f4e() { return new Orbital(4, 3, 1); }
	public static Orbital f4f() { return new Orbital(4, 3, 2); }
	public static Orbital f4g() { return new Orbital(4, 3, 3); }
	
	public static Orbital f5a() { return new Orbital(5, 3, -3); }
	public static Orbital f5b() { return new Orbital(5, 3, -2); }
	public static Orbital f5c() { return new Orbital(5, 3, -1); }
	public static Orbital f5d() { return new Orbital(5, 3, 0); }
	public static Orbital f5e() { return new Orbital(5, 3, 1); }
	public static Orbital f5f() { return new Orbital(5, 3, 2); }
	public static Orbital f5g() { return new Orbital(5, 3, 3); }
	
	public static Orbital f6a() { return new Orbital(6, 3, -3); }
	public static Orbital f6b() { return new Orbital(6, 3, -2); }
	public static Orbital f6c() { return new Orbital(6, 3, -1); }
	public static Orbital f6d() { return new Orbital(6, 3, 0); }
	public static Orbital f6e() { return new Orbital(6, 3, 1); }
	public static Orbital f6f() { return new Orbital(6, 3, 2); }
	public static Orbital f6g() { return new Orbital(6, 3, 3); }
	
	public static Orbital f7a() { return new Orbital(7, 3, -3); }
	public static Orbital f7b() { return new Orbital(7, 3, -2); }
	public static Orbital f7c() { return new Orbital(7, 3, -1); }
	public static Orbital f7d() { return new Orbital(7, 3, 0); }
	public static Orbital f7e() { return new Orbital(7, 3, 1); }
	public static Orbital f7f() { return new Orbital(7, 3, 2); }
	public static Orbital f7g() { return new Orbital(7, 3, 3); }
}
