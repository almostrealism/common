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

/**
 * @author  Michael Murray
 */
public class Shell {
	private SubShell s[];
	
	private int energyLevel;
	
	public Shell(SubShell... s) {
		this.s = s;
		this.energyLevel = s.length > 0 ? s[0].getOrbital().getPrincipal() : 0;
		for (SubShell ss : s) if (ss.getOrbital().getPrincipal() != energyLevel) {
			throw new IllegalArgumentException("All SubShells in a Shell must share the same principal quantum number");
		}
	}
	
	public int getEnergyLevel() { return energyLevel; }
	
	public Shell merge(Shell sh) {
		if (sh.getEnergyLevel() != this.getEnergyLevel()) {
			throw new IllegalArgumentException(sh + " is not the same energy level as " + this);
		}
		
		List<SubShell> l = new ArrayList<>();
		for (SubShell ss : s) l.add(ss);
		for (SubShell ss : sh.s) l.add(ss);
		return new Shell(l.toArray(new SubShell[0]));
	}

	public Iterable<SubShell> subShells() { return Arrays.asList(s); }

	/**
	 * A new {@link Electrons} instance is returned every time.
	 */
	public Electrons getElectrons(int protons) {
		List<Electron> e = new ArrayList<>();

		for (SubShell s : subShells()) {
			Electron up = s.getElectron(Spin.Up, protons);
			Electron down = s.getElectron(Spin.Down, protons);
			if (up != null) e.add(up);
			if (down != null) e.add(down);
		}

		return new Electrons(e.toArray(new Electron[0]));
	}

	public String toString() { return "Shell[" + getEnergyLevel() + "]"; }

	public static Shell first(int s) {
		return s1(s);
	}
	
	public static Shell second(int s, int p) {
		int pp[] = p(p);
		return second(s, pp[0], pp[1], pp[2]);
	}
	
	public static Shell second(int s, int px, int py, int pz) {
		return s2(s).merge(p2(px, py, pz));
	}
	
	public static Shell third(int s, int p, int d) {
		int pp[] = p(p);
		int dd[] = d(d);
		return third(s, pp[0], pp[1], pp[2], dd[0], dd[1], dd[2], dd[3], dd[4]);
	}
	
	public static Shell third(int s, int px, int py, int pz, int da, int db, int dc, int dd, int de) {
		if (da != 0 || db != 0 || dc != 0 || dd != 0 || de != 0) {
			return s3(s).merge(p3(px, py, pz)).merge(d3(da, db, dc, dd, de));
		} else {
			return s3(s).merge(p3(px, py, pz));
		}
	}
	
	public static Shell fourth(int s, int p, int d, int f) {
		int pp[] = p(p);
		int dd[] = d(d);
		int ff[] = f(f);
		return fourth(s, pp[0], pp[1], pp[2], dd[0], dd[1], dd[2], dd[3], dd[4],
						ff[0], ff[1], ff[2], ff[3], ff[4], ff[5], ff[6]);
	}
	
	public static Shell fourth(int s, int px, int py, int pz, int da, int db, int dc, int dd, int de,
								int fa, int fb, int fc, int fd, int fe, int ff, int fg) {
		return s4(s).merge(p4(px, py, pz)).merge(d4(da, db, dc, dd, de)).merge(f4(fa, fb, fc, fd, fe, ff, fg));
	}
	
	public static Shell fifth(int s, int p, int d, int f) {
		int pp[] = p(p);
		int dd[] = d(d);
		int ff[] = f(f);
		return fifth(s, pp[0], pp[1], pp[2], dd[0], dd[1], dd[2], dd[3], dd[4],
						ff[0], ff[1], ff[2], ff[3], ff[4], ff[5], ff[6]);
	}
	
	public static Shell fifth(int s, int px, int py, int pz, int da, int db, int dc, int dd, int de,
								int fa, int fb, int fc, int fd, int fe, int ff, int fg) {
		return s5(s).merge(p5(px, py, pz)).merge(d5(da, db, dc, dd, de)).merge(f5(fa, fb, fc, fd, fe, ff, fg));
	}
	
	public static Shell sixth(int s, int p, int d, int f) {
		int pp[] = p(p);
		int dd[] = d(d);
		int ff[] = f(f);
		return sixth(s, pp[0], pp[1], pp[2], dd[0], dd[1], dd[2], dd[3], dd[4],
						ff[0], ff[1], ff[2], ff[3], ff[4], ff[5], ff[6]);
	}
	
	public static Shell sixth(int s, int px, int py, int pz, int da, int db, int dc, int dd, int de,
								int fa, int fb, int fc, int fd, int fe, int ff, int fg) {
		return s6(s).merge(p6(px, py, pz)).merge(d6(da, db, dc, dd, de)).merge(f6(fa, fb, fc, fd, fe, ff, fg));
	}
	
	public static Shell seventh(int s, int p, int d, int f) {
		int pp[] = p(p);
		int dd[] = d(d);
		int ff[] = f(f);
		return sixth(s, pp[0], pp[1], pp[2], dd[0], dd[1], dd[2], dd[3], dd[4],
						ff[0], ff[1], ff[2], ff[3], ff[4], ff[5], ff[6]);
	}
	
	public static Shell seventh(int s, int px, int py, int pz, int da, int db, int dc, int dd, int de,
								int fa, int fb, int fc, int fd, int fe, int ff, int fg) {
		return s7(s).merge(p7(px, py, pz)).merge(d7(da, db, dc, dd, de)).merge(f7(fa, fb, fc, fd, fe, ff, fg));
	}
	
	public static Shell s1(int electrons) { return new Shell(Orbital.s1().populate(electrons)); }
	public static Shell s2(int electrons) { return new Shell(Orbital.s2().populate(electrons)); }
	public static Shell s3(int electrons) { return new Shell(Orbital.s3().populate(electrons)); }
	public static Shell s4(int electrons) { return new Shell(Orbital.s4().populate(electrons)); }
	public static Shell s5(int electrons) { return new Shell(Orbital.s5().populate(electrons)); }
	public static Shell s6(int electrons) { return new Shell(Orbital.s6().populate(electrons)); }
	public static Shell s7(int electrons) { return new Shell(Orbital.s7().populate(electrons)); }
	
	public static Shell p2(int x, int y, int z) {
		return new Shell(Orbital.p2x().populate(x),
						Orbital.p2y().populate(y),
						Orbital.p2z().populate(z));
	}
	
	public static Shell p3(int x, int y, int z) {
		return new Shell(Orbital.p3x().populate(x),
						Orbital.p3y().populate(y),
						Orbital.p3z().populate(z));
	}
	
	public static Shell p4(int x, int y, int z) {
		return new Shell(Orbital.p4x().populate(x),
						Orbital.p4y().populate(y),
						Orbital.p4z().populate(z));
	}

	public static Shell p5(int x, int y, int z) {
		return new Shell(Orbital.p5x().populate(x),
						Orbital.p5y().populate(y),
						Orbital.p5z().populate(z));
	}
	
	public static Shell p6(int x, int y, int z) {
		return new Shell(Orbital.p6x().populate(x),
						Orbital.p6y().populate(y),
						Orbital.p6z().populate(z));
	}
	
	public static Shell p7(int x, int y, int z) {
		return new Shell(Orbital.p7x().populate(x),
						Orbital.p7y().populate(y),
						Orbital.p7z().populate(z));
	}
	
	public static Shell d3(int a, int b, int c, int d, int e) {
		List<SubShell> s = new ArrayList<>();
		if (a > 0) s.add(Orbital.d3a().populate(a));
		if (b > 0) s.add(Orbital.d3b().populate(b));
		if (c > 0) s.add(Orbital.d3c().populate(c));
		if (d > 0) s.add(Orbital.d3d().populate(d));
		if (e > 0) s.add(Orbital.d3e().populate(e));
		return new Shell(s.toArray(new SubShell[0]));
	}
	
	public static Shell d4(int a, int b, int c, int d, int e) {
		return new Shell(Orbital.d4a().populate(a),
						Orbital.d4b().populate(b),
						Orbital.d4c().populate(c),
						Orbital.d4d().populate(d),
						Orbital.d4e().populate(e));
	}
	
	public static Shell d5(int a, int b, int c, int d, int e) {
		return new Shell(Orbital.d5a().populate(a),
						Orbital.d5b().populate(b),
						Orbital.d5c().populate(c),
						Orbital.d5d().populate(d),
						Orbital.d5e().populate(e));
	}
	
	public static Shell d6(int a, int b, int c, int d, int e) {
		return new Shell(Orbital.d6a().populate(a),
						Orbital.d6b().populate(b),
						Orbital.d6c().populate(c),
						Orbital.d6d().populate(d),
						Orbital.d6e().populate(e));
	}
	
	public static Shell d7(int a, int b, int c, int d, int e) {
		return new Shell(Orbital.d7a().populate(a),
						Orbital.d7b().populate(b),
						Orbital.d7c().populate(c),
						Orbital.d7d().populate(d),
						Orbital.d7e().populate(e));
	}
	
	public static Shell f4(int a, int b, int c, int d, int e, int f, int g) {
		return new Shell(Orbital.f4a().populate(a),
						Orbital.f4b().populate(b),
						Orbital.f4c().populate(c),
						Orbital.f4d().populate(d),
						Orbital.f4e().populate(e),
						Orbital.f4f().populate(f),
						Orbital.f4g().populate(g));
	}
	
	public static Shell f5(int a, int b, int c, int d, int e, int f, int g) {
		return new Shell(Orbital.f5a().populate(a),
						Orbital.f5b().populate(b),
						Orbital.f5c().populate(c),
						Orbital.f5d().populate(d),
						Orbital.f5e().populate(e),
						Orbital.f5f().populate(f),
						Orbital.f5g().populate(g));
	}
	
	public static Shell f6(int a, int b, int c, int d, int e, int f, int g) {
		return new Shell(Orbital.f6a().populate(a),
						Orbital.f6b().populate(b),
						Orbital.f6c().populate(c),
						Orbital.f6d().populate(d),
						Orbital.f6e().populate(e),
						Orbital.f6f().populate(f),
						Orbital.f6g().populate(g));
	}
	
	public static Shell f7(int a, int b, int c, int d, int e, int f, int g) {
		return new Shell(Orbital.f7a().populate(a),
						Orbital.f7b().populate(b),
						Orbital.f7c().populate(c),
						Orbital.f7d().populate(d),
						Orbital.f7e().populate(e),
						Orbital.f7f().populate(f),
						Orbital.f7g().populate(g));
	}
	
	private static int[] p(int p) {
		int px = 0;
		int py = 0;
		int pz = 0;
		
		switch (p) {
			case 1:
				px = 1; break;
			case 2:
				px = 1; py = 1; break;
			case 3:
				px = 1; py = 1; pz = 1; break;
			case 4:
				px = 2; py = 1; pz = 1; break;
			case 5:
				px = 2; py = 2; pz = 1; break;
			case 6:
				px = 2; py = 2; pz = 2; break;
		}
		
		return new int[] { px, py, pz };
	}
	
	private static int[] d(int d) {
		int da = 0;
		int db = 0;
		int dc = 0;
		int dd = 0;
		int de = 0;
		
		switch (d) {
			case 1:
				da = 1; break;
			case 2:
				da = 1; db = 1; break;
			case 3:
				da = 1; db = 1; dc = 1; break;
			case 4:
				da = 1; db = 1; dc = 1; dd = 1; break;
			case 5:
				da = 1; db = 1; dc = 1; dd = 1; de = 1; break;
			case 6:
				da = 2; db = 1; dc = 1; dd = 1; de = 1; break;
			case 7:
				da = 2; db = 2; dc = 1; dd = 1; de = 1; break;
			case 8:
				da = 2; db = 2; dc = 2; dd = 1; de = 1; break;
			case 9:
				da = 2; db = 2; dc = 2; dd = 2; de = 1; break;
			case 10:
				da = 2; db = 2; dc = 2; dd = 2; de = 2; break;
		}
		
		return new int[] { da, db, dc, dd, de };
	}
	private static int[] f(int f) {
		int fa = 0;
		int fb = 0;
		int fc = 0;
		int fd = 0;
		int fe = 0;
		int ff = 0;
		int fg = 0;
		
		switch (f) {
			case 1:
				fa = 1; break;
			case 2:
				fa = 1; fb = 1; break;
			case 3:
				fa = 1; fb = 1; fc = 1; break;
			case 4:
				fa = 1; fb = 1; fc = 1; fd = 1; break;
			case 5:
				fa = 1; fb = 1; fc = 1; fd = 1; fe = 1; break;
			case 6:
				fa = 1; fb = 1; fc = 1; fd = 1; fe = 1; ff = 1; break;
			case 7:
				fa = 1; fb = 1; fc = 1; fd = 1; fe = 1; ff = 1; fg = 1; break;
			case 8:
				fa = 2; fb = 1; fc = 1; fd = 1; fe = 1; ff = 1; fg = 1; break;
			case 9:
				fa = 2; fb = 2; fc = 1; fd = 1; fe = 1; ff = 1; fg = 1; break;
			case 10:
				fa = 2; fb = 2; fc = 2; fd = 1; fe = 1; ff = 1; fg = 1; break;
			case 11:
				fa = 2; fb = 2; fc = 2; fd = 2; fe = 1; ff = 1; fg = 1; break;
			case 12:
				fa = 2; fb = 2; fc = 2; fd = 2; fe = 2; ff = 1; fg = 1; break;
			case 13:
				fa = 2; fb = 2; fc = 2; fd = 2; fe = 2; ff = 2; fg = 1; break;
			case 14:
				fa = 2; fb = 2; fc = 2; fd = 2; fe = 2; ff = 2; fg = 2; break;
		}
		
		return new int[] { fa, fb, fc, fd, fe, ff, fg };
	}
}
