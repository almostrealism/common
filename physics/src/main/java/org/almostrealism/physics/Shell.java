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
 * Represents an electron shell in an atom's electronic structure.
 * <p>
 * A shell is a collection of {@link SubShell}s that share the same principal quantum number (n).
 * In atomic physics, shells are typically named K, L, M, N, O, P, Q corresponding to
 * principal quantum numbers 1, 2, 3, 4, 5, 6, 7 respectively. Each shell can contain
 * multiple subshells (s, p, d, f) based on the angular momentum quantum number.
 * </p>
 *
 * <h2>Shell Configuration</h2>
 * <p>
 * Shells are organized hierarchically:
 * </p>
 * <ul>
 *   <li><b>Shell (n=1, K)</b>: Contains only s subshell (1s), max 2 electrons</li>
 *   <li><b>Shell (n=2, L)</b>: Contains s and p subshells (2s, 2p), max 8 electrons</li>
 *   <li><b>Shell (n=3, M)</b>: Contains s, p, d subshells (3s, 3p, 3d), max 18 electrons</li>
 *   <li><b>Shell (n=4, N)</b>: Contains s, p, d, f subshells (4s, 4p, 4d, 4f), max 32 electrons</li>
 * </ul>
 *
 * <h2>Factory Methods</h2>
 * <p>
 * The class provides convenient factory methods for creating shells:
 * </p>
 * <pre>{@code
 * // First shell (K) with 2 electrons in 1s
 * Shell k = Shell.first(2);
 *
 * // Second shell (L) with 2 electrons in 2s and 3 in 2p
 * Shell l = Shell.second(2, 3);
 *
 * // Third shell (M) with 2 in 3s, 6 in 3p, 5 in 3d
 * Shell m = Shell.third(2, 6, 5);
 * }</pre>
 *
 * <h2>Shell Merging</h2>
 * <p>
 * Shells with the same energy level can be merged using the {@link #merge(Shell)} method.
 * This is useful when building atoms from partial shell configurations.
 * </p>
 *
 * @author Michael Murray
 * @see SubShell
 * @see Orbital
 * @see Atom
 * @see Electron
 */
public class Shell {
	private SubShell s[];

	private int energyLevel;

	/**
	 * Constructs a shell containing the specified subshells.
	 * <p>
	 * All subshells must share the same principal quantum number. The energy level
	 * of the shell is determined from the first subshell's orbital.
	 * </p>
	 *
	 * @param s the subshells to include in this shell
	 * @throws IllegalArgumentException if subshells have different principal quantum numbers
	 */
	public Shell(SubShell... s) {
		this.s = s;
		this.energyLevel = s.length > 0 ? s[0].getOrbital().getPrincipal() : 0;
		for (SubShell ss : s) if (ss.getOrbital().getPrincipal() != energyLevel) {
			throw new IllegalArgumentException("All SubShells in a Shell must share the same principal quantum number");
		}
	}
	
	/**
	 * Returns the energy level (principal quantum number) of this shell.
	 *
	 * @return the principal quantum number (1 for K shell, 2 for L shell, etc.)
	 */
	public int getEnergyLevel() { return energyLevel; }

	/**
	 * Merges this shell with another shell of the same energy level.
	 * <p>
	 * This method combines the subshells from both shells into a new shell.
	 * Both shells must have the same energy level (principal quantum number).
	 * </p>
	 *
	 * @param sh the shell to merge with this shell
	 * @return a new shell containing all subshells from both shells
	 * @throws IllegalArgumentException if the shells have different energy levels
	 */
	public Shell merge(Shell sh) {
		if (sh.getEnergyLevel() != this.getEnergyLevel()) {
			throw new IllegalArgumentException(sh + " is not the same energy level as " + this);
		}
		
		List<SubShell> l = new ArrayList<>();
		for (SubShell ss : s) l.add(ss);
		for (SubShell ss : sh.s) l.add(ss);
		return new Shell(l.toArray(new SubShell[0]));
	}

	/**
	 * Returns an iterable over all subshells in this shell.
	 *
	 * @return an iterable of {@link SubShell} objects
	 */
	public Iterable<SubShell> subShells() { return Arrays.asList(s); }

	/**
	 * Creates a new {@link Electrons} instance containing all electrons in this shell.
	 * <p>
	 * A new instance is created on each invocation. The electrons are configured
	 * with excitation energy levels based on the specified proton count of the
	 * parent atom, which affects the binding energies.
	 * </p>
	 *
	 * @param protons the number of protons in the parent atom (affects energy calculations)
	 * @return a new {@link Electrons} instance containing all electrons in this shell
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

	/**
	 * Returns a string representation of this shell.
	 *
	 * @return a string in the format "Shell[n]" where n is the energy level
	 */
	public String toString() { return "Shell[" + getEnergyLevel() + "]"; }

	/**
	 * Creates the first electron shell (K shell, n=1) with the specified number of s electrons.
	 *
	 * @param s the number of electrons in the 1s subshell (0-2)
	 * @return a new Shell representing the K shell
	 */
	public static Shell first(int s) {
		return s1(s);
	}
	
	/**
	 * Creates the second electron shell (L shell, n=2) with specified s and p electrons.
	 *
	 * @param s the number of electrons in the 2s subshell (0-2)
	 * @param p the total number of electrons in 2p subshells (0-6)
	 * @return a new Shell representing the L shell
	 */
	public static Shell second(int s, int p) {
		int pp[] = p(p);
		return second(s, pp[0], pp[1], pp[2]);
	}

	/**
	 * Creates the second electron shell (L shell, n=2) with detailed p orbital specification.
	 *
	 * @param s  the number of electrons in the 2s subshell (0-2)
	 * @param px the number of electrons in the 2px orbital (0-2)
	 * @param py the number of electrons in the 2py orbital (0-2)
	 * @param pz the number of electrons in the 2pz orbital (0-2)
	 * @return a new Shell representing the L shell
	 */
	public static Shell second(int s, int px, int py, int pz) {
		return s2(s).merge(p2(px, py, pz));
	}

	/**
	 * Creates the third electron shell (M shell, n=3) with specified s, p, and d electrons.
	 *
	 * @param s the number of electrons in the 3s subshell (0-2)
	 * @param p the total number of electrons in 3p subshells (0-6)
	 * @param d the total number of electrons in 3d subshells (0-10)
	 * @return a new Shell representing the M shell
	 */
	public static Shell third(int s, int p, int d) {
		int pp[] = p(p);
		int dd[] = d(d);
		return third(s, pp[0], pp[1], pp[2], dd[0], dd[1], dd[2], dd[3], dd[4]);
	}
	
	public static Shell third(int s, int px, int py, int pz, int da, int db, int dc, int dd, int de) {
		if (s == 0) {
			if (px != 0 || py != 0 || pz != 0) {
				if (da != 0 || db != 0 || dc != 0 || dd != 0 || de != 0) {
					return p3(px, py, pz).merge(d3(da, db, dc, dd, de));
				} else {
					return p3(px, py, pz);
				}
			} else {
				if (da != 0 || db != 0 || dc != 0 || dd != 0 || de != 0) {
					return d3(da, db, dc, dd, de);
				} else {
					return null;
				}
			}
		} else {
			if (px != 0 || py != 0 || pz != 0) {
				if (da != 0 || db != 0 || dc != 0 || dd != 0 || de != 0) {
					return s3(s).merge(p3(px, py, pz)).merge(d3(da, db, dc, dd, de));
				} else {
					return s3(s).merge(p3(px, py, pz));
				}
			} else {
				if (da != 0 || db != 0 || dc != 0 || dd != 0 || de != 0) {
					return s3(s).merge(d3(da, db, dc, dd, de));
				} else {
					return s3(s);
				}
			}
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
		List<Shell> sl = new ArrayList<>();
		if (s > 0) sl.add(s4(s));
		if (px != 0 || py != 0 || pz != 0) sl.add(p4(px, py, pz));
		if (da != 0 || db != 0 || dc != 0 || dd != 0 || de != 0) sl.add(d4(da, db, dc, dd, de));
		if (fa != 0 || fb != 0 || fc != 0 || fd != 0 || fe != 0 || ff != 0 || fg != 0) sl.add(f4(fa, fb, fc, fd, fe, ff, fg));
		return merge(sl);
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
		List<Shell> sl = new ArrayList<>();
		if (s > 0) sl.add(s5(s));
		if (px != 0 || py != 0 || pz != 0) sl.add(p5(px, py, pz));
		if (da != 0 || db != 0 || dc != 0 || dd != 0 || de != 0) sl.add(d5(da, db, dc, dd, de));
		if (fa != 0 || fb != 0 || fc != 0 || fd != 0 || fe != 0 || ff != 0 || fg != 0) sl.add(f5(fa, fb, fc, fd, fe, ff, fg));
		return merge(sl);
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
		List<Shell> sl = new ArrayList<>();
		if (s > 0) sl.add(s6(s));
		if (px != 0 || py != 0 || pz != 0) sl.add(p6(px, py, pz));
		if (da != 0 || db != 0 || dc != 0 || dd != 0 || de != 0) sl.add(d6(da, db, dc, dd, de));
		if (fa != 0 || fb != 0 || fc != 0 || fd != 0 || fe != 0 || ff != 0 || fg != 0) sl.add(f6(fa, fb, fc, fd, fe, ff, fg));
		return merge(sl);
	}
	
	public static Shell seventh(int s, int p, int d, int f) {
		int pp[] = p(p);
		int dd[] = d(d);
		int ff[] = f(f);
		return seventh(s, pp[0], pp[1], pp[2], dd[0], dd[1], dd[2], dd[3], dd[4],
						ff[0], ff[1], ff[2], ff[3], ff[4], ff[5], ff[6]);
	}
	
	public static Shell seventh(int s, int px, int py, int pz, int da, int db, int dc, int dd, int de,
								int fa, int fb, int fc, int fd, int fe, int ff, int fg) {
		List<Shell> sl = new ArrayList<>();
		if (s > 0) sl.add(s7(s));
		if (px != 0 || py != 0 || pz != 0) sl.add(p7(px, py, pz));
		if (da != 0 || db != 0 || dc != 0 || dd != 0 || de != 0) sl.add(d7(da, db, dc, dd, de));
		if (fa != 0 || fb != 0 || fc != 0 || fd != 0 || fe != 0 || ff != 0 || fg != 0) sl.add(f7(fa, fb, fc, fd, fe, ff, fg));
		return merge(sl);
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

	protected static Shell merge(List<Shell> s) {
		if (s.size() == 0) return null;
		if (s.size() == 1) return s.get(0);

		Shell m = s.get(0);
		for (int i = 1; i < s.size(); i++) {
			m = m.merge(s.get(i));
		}

		return m;
	}
}
