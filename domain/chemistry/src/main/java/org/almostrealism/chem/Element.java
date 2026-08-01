/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Represents all 118 chemical elements of the periodic table.
 *
 * <p>Each enum constant encapsulates the atomic number and electron shell
 * configuration needed to construct an {@link Atom}. The {@code Element} enum
 * implements {@link Atomic}, providing the {@link #construct()} method to
 * create a fully-configured {@link Atom} instance with the correct electron
 * shells.</p>
 *
 * <h2>Accessing Individual Elements</h2>
 * <pre>{@code
 * Element hydrogen = Element.Hydrogen;
 * Element carbon = Element.Carbon;
 * Element gold = Element.Gold;
 *
 * // Construct atoms
 * Atom carbonAtom = carbon.construct();
 *
 * // Get atomic number
 * int z = carbon.getAtomicNumber();  // Returns 6
 * }</pre>
 *
 * <h2>Accessing Element Groups</h2>
 * <pre>{@code
 * List<Element> alkaliMetals = Element.alkaliMetals();
 * List<Element> nobleGases = Element.nobleGasses();
 * List<Element> halogens = Element.halogens();
 * List<Element> transitionMetals = Element.transitionMetals();
 *
 * // By orbital block
 * List<Element> sBlock = Element.sBlock();
 * List<Element> pBlock = Element.pBlock();
 * }</pre>
 *
 * <h2>Accessing by Period or Group</h2>
 * <pre>{@code
 * List<Element> period1 = Element.Periods.first();
 * List<Element> period2 = Element.Periods.second();
 * List<Element> group1 = Element.Groups.first();
 * List<Element> group18 = Element.Groups.eigthteenth();
 * }</pre>
 *
 * @see Atom
 * @see Atomic
 * @see Shell
 * @see Alloy
 *
 * @author Michael Murray
 */
public enum Element implements Atomic {

	// Period 1
	/** Hydrogen (H) - Atomic number 1. Electron configuration: 1s1. */
	Hydrogen(1, () -> List.of(Shell.first(1))),
	/** Helium (He) - Atomic number 2. Electron configuration: 1s2. Noble gas. */
	Helium(2, () -> List.of(Shell.first(2))),

	// Period 2
	/** Lithium (Li) - Atomic number 3. Electron configuration: [He] 2s1. */
	Lithium(3, () -> shells(Helium, Shell.second(1, 0))),
	/** Beryllium (Be) - Atomic number 4. Electron configuration: [He] 2s2. */
	Beryllium(4, () -> shells(Helium, Shell.second(2, 0))),
	/** Boron (B) - Atomic number 5. Electron configuration: [He] 2s2 2p1. */
	Boron(5, () -> shells(Helium, Shell.second(2, 1))),
	/** Carbon (C) - Atomic number 6. Electron configuration: [He] 2s2 2p2. */
	Carbon(6, () -> shells(Helium, Shell.second(2, 2))),
	/** Nitrogen (N) - Atomic number 7. Electron configuration: [He] 2s2 2p3. */
	Nitrogen(7, () -> shells(Helium, Shell.second(2, 3))),
	/** Oxygen (O) - Atomic number 8. Electron configuration: [He] 2s2 2p4. */
	Oxygen(8, () -> shells(Helium, Shell.second(2, 4))),
	/** Fluorine (F) - Atomic number 9. Electron configuration: [He] 2s2 2p5. */
	Fluorine(9, () -> shells(Helium, Shell.second(2, 5))),
	/** Neon (Ne) - Atomic number 10. Electron configuration: [He] 2s2 2p6. Noble gas. */
	Neon(10, () -> shells(Helium, Shell.second(2, 6))),

	// Period 3
	/** Sodium (Na) - Atomic number 11. Electron configuration: [Ne] 3s1. */
	Sodium(11, () -> shells(Neon, Shell.third(1, 0, 0))),
	/** Magnesium (Mg) - Atomic number 12. Electron configuration: [Ne] 3s2. */
	Magnesium(12, () -> shells(Neon, Shell.third(2, 0, 0))),
	/** Aluminium (Al) - Atomic number 13. Electron configuration: [Ne] 3s2 3p1. */
	Aluminium(13, () -> shells(Neon, Shell.third(2, 1, 0))),
	/** Silicon (Si) - Atomic number 14. Electron configuration: [Ne] 3s2 3p2. */
	Silicon(14, () -> shells(Neon, Shell.third(2, 2, 0))),
	/** Phosphorus (P) - Atomic number 15. Electron configuration: [Ne] 3s2 3p3. */
	Phosphorus(15, () -> shells(Neon, Shell.third(2, 3, 0))),
	/** Sulfur (S) - Atomic number 16. Electron configuration: [Ne] 3s2 3p4. */
	Sulfur(16, () -> shells(Neon, Shell.third(2, 4, 0))),
	/** Chlorine (Cl) - Atomic number 17. Electron configuration: [Ne] 3s2 3p5. */
	Chlorine(17, () -> shells(Neon, Shell.third(2, 5, 0))),
	/** Argon (Ar) - Atomic number 18. Electron configuration: [Ne] 3s2 3p6. Noble gas. */
	Argon(18, () -> shells(Neon, Shell.third(2, 6, 0))),

	// Period 4
	/** Potassium (K) - Atomic number 19. Electron configuration: [Ar] 4s1. */
	Potassium(19, () -> shells(Argon, Shell.fourth(1, 0, 0, 0))),
	/** Calcium (Ca) - Atomic number 20. Electron configuration: [Ar] 4s2. */
	Calcium(20, () -> shells(Argon, Shell.fourth(2, 0, 0, 0))),
	/** Scandium (Sc) - Atomic number 21. Electron configuration: [Ar] 3d1 4s2. */
	Scandium(21, () -> shells(Argon, Shell.third(0, 0, 1), Shell.fourth(2, 0, 0, 0))),
	/** Titanium (Ti) - Atomic number 22. Electron configuration: [Ar] 3d2 4s2. */
	Titanium(22, () -> shells(Argon, Shell.third(0, 0, 2), Shell.fourth(2, 0, 0, 0))),
	/** Vanadium (V) - Atomic number 23. Electron configuration: [Ar] 3d3 4s2. */
	Vanadium(23, () -> shells(Argon, Shell.third(0, 0, 3), Shell.fourth(2, 0, 0, 0))),
	/** Chromium (Cr) - Atomic number 24. Electron configuration: [Ar] 3d5 4s1. */
	Chromium(24, () -> shells(Argon, Shell.third(0, 0, 5), Shell.fourth(1, 0, 0, 0))),
	/** Manganese (Mn) - Atomic number 25. Electron configuration: [Ar] 3d5 4s2. */
	Manganese(25, () -> shells(Argon, Shell.third(0, 0, 5), Shell.fourth(2, 0, 0, 0))),
	/** Iron (Fe) - Atomic number 26. Electron configuration: [Ar] 3d6 4s2. */
	Iron(26, () -> shells(Argon, Shell.third(0, 0, 6), Shell.fourth(2, 0, 0, 0))),
	/** Cobalt (Co) - Atomic number 27. Electron configuration: [Ar] 3d7 4s2. */
	Cobalt(27, () -> shells(Argon, Shell.third(0, 0, 7), Shell.fourth(2, 0, 0, 0))),
	/** Nickel (Ni) - Atomic number 28. Electron configuration: [Ar] 3d8 4s2. */
	Nickel(28, () -> shells(Argon, Shell.third(0, 0, 8), Shell.fourth(2, 0, 0, 0))),
	/** Copper (Cu) - Atomic number 29. Electron configuration: [Ar] 3d10 4s1. */
	Copper(29, () -> shells(Argon, Shell.third(0, 0, 10), Shell.fourth(1, 0, 0, 0))),
	/** Zinc (Zn) - Atomic number 30. Electron configuration: [Ar] 3d10 4s2. */
	Zinc(30, () -> shells(Argon, Shell.third(0, 0, 10), Shell.fourth(2, 0, 0, 0))),
	/** Gallium (Ga) - Atomic number 31. Electron configuration: [Ar] 3d10 4s2 4p1. */
	Gallium(31, () -> shells(Argon, Shell.third(0, 0, 10), Shell.fourth(2, 1, 0, 0))),
	/** Germanium (Ge) - Atomic number 32. Electron configuration: [Ar] 3d10 4s2 4p2. */
	Germanium(32, () -> shells(Argon, Shell.third(0, 0, 10), Shell.fourth(2, 2, 0, 0))),
	/** Arsenic (As) - Atomic number 33. Electron configuration: [Ar] 3d10 4s2 4p3. */
	Arsenic(33, () -> shells(Argon, Shell.third(0, 0, 10), Shell.fourth(2, 3, 0, 0))),
	/** Selenium (Se) - Atomic number 34. Electron configuration: [Ar] 3d10 4s2 4p4. */
	Selenium(34, () -> shells(Argon, Shell.third(0, 0, 10), Shell.fourth(2, 4, 0, 0))),
	/** Bromine (Br) - Atomic number 35. Electron configuration: [Ar] 3d10 4s2 4p5. */
	Bromine(35, () -> shells(Argon, Shell.third(0, 0, 10), Shell.fourth(2, 5, 0, 0))),
	/** Krypton (Kr) - Atomic number 36. Electron configuration: [Ar] 3d10 4s2 4p6. Noble gas. */
	Krypton(36, () -> shells(Argon, Shell.third(0, 0, 10), Shell.fourth(2, 6, 0, 0))),

	// Period 5
	/** Rubidium (Rb) - Atomic number 37. Electron configuration: [Kr] 5s1. */
	Rubidium(37, () -> shells(Krypton, Shell.fifth(1, 0, 0, 0))),
	/** Strontium (Sr) - Atomic number 38. Electron configuration: [Kr] 5s2. */
	Strontium(38, () -> shells(Krypton, Shell.fifth(2, 0, 0, 0))),
	/** Yttrium (Y) - Atomic number 39. Electron configuration: [Kr] 4d1 5s2. */
	Yttrium(39, () -> shells(Krypton, Shell.fourth(0, 0, 1, 0), Shell.fifth(2, 0, 0, 0))),
	/** Zirconium (Zr) - Atomic number 40. Electron configuration: [Kr] 4d2 5s2. */
	Zirconium(40, () -> shells(Krypton, Shell.fourth(0, 0, 2, 0), Shell.fifth(2, 0, 0, 0))),
	/** Niobium (Nb) - Atomic number 41. Electron configuration: [Kr] 4d4 5s1. */
	Niobium(41, () -> shells(Krypton, Shell.fourth(0, 0, 4, 0), Shell.fifth(1, 0, 0, 0))),
	/** Molybdenum (Mo) - Atomic number 42. Electron configuration: [Kr] 4d5 5s1. */
	Molybdenum(42, () -> shells(Krypton, Shell.fourth(0, 0, 5, 0), Shell.fifth(1, 0, 0, 0))),
	/** Technetium (Tc) - Atomic number 43. Electron configuration: [Kr] 4d5 5s2. */
	Technetium(43, () -> shells(Krypton, Shell.fourth(0, 0, 5, 0), Shell.fifth(2, 0, 0, 0))),
	/** Ruthenium (Ru) - Atomic number 44. Electron configuration: [Kr] 4d7 5s1. */
	Ruthenium(44, () -> shells(Krypton, Shell.fourth(0, 0, 7, 0), Shell.fifth(1, 0, 0, 0))),
	/** Rhodium (Rh) - Atomic number 45. Electron configuration: [Kr] 4d8 5s1. */
	Rhodium(45, () -> shells(Krypton, Shell.fourth(0, 0, 8, 0), Shell.fifth(1, 0, 0, 0))),
	/** Palladium (Pd) - Atomic number 46. Electron configuration: [Kr] 4d10. */
	Palladium(46, () -> shells(Krypton, Shell.fourth(0, 0, 10, 0))),
	/** Silver (Ag) - Atomic number 47. Electron configuration: [Kr] 4d10 5s1. */
	Silver(47, () -> shells(Krypton, Shell.fourth(0, 0, 10, 0), Shell.fifth(1, 0, 0, 0))),
	/** Cadmium (Cd) - Atomic number 48. Electron configuration: [Kr] 4d10 5s2. */
	Cadmium(48, () -> shells(Krypton, Shell.fourth(0, 0, 10, 0), Shell.fifth(2, 0, 0, 0))),
	/** Indium (In) - Atomic number 49. Electron configuration: [Kr] 4d10 5s2 5p1. */
	Indium(49, () -> shells(Krypton, Shell.fourth(0, 0, 10, 0), Shell.fifth(2, 1, 0, 0))),
	/** Tin (Sn) - Atomic number 50. Electron configuration: [Kr] 4d10 5s2 5p2. */
	Tin(50, () -> shells(Krypton, Shell.fourth(0, 0, 10, 0), Shell.fifth(2, 2, 0, 0))),
	/** Antimony (Sb) - Atomic number 51. Electron configuration: [Kr] 4d10 5s2 5p3. */
	Antimony(51, () -> shells(Krypton, Shell.fourth(0, 0, 10, 0), Shell.fifth(2, 3, 0, 0))),
	/** Tellurium (Te) - Atomic number 52. Electron configuration: [Kr] 4d10 5s2 5p4. */
	Tellurium(52, () -> shells(Krypton, Shell.fourth(0, 0, 10, 0), Shell.fifth(2, 4, 0, 0))),
	/** Iodine (I) - Atomic number 53. Electron configuration: [Kr] 4d10 5s2 5p5. */
	Iodine(53, () -> shells(Krypton, Shell.fourth(0, 0, 10, 0), Shell.fifth(2, 5, 0, 0))),
	/** Xenon (Xe) - Atomic number 54. Electron configuration: [Kr] 4d10 5s2 5p6. Noble gas. */
	Xenon(54, () -> shells(Krypton, Shell.fourth(0, 0, 10, 0), Shell.fifth(2, 6, 0, 0))),

	// Period 6
	/** Caesium (Cs) - Atomic number 55. Electron configuration: [Xe] 6s1. */
	Caesium(55, () -> shells(Xenon, Shell.sixth(1, 0, 0, 0))),
	/** Barium (Ba) - Atomic number 56. Electron configuration: [Xe] 6s2. */
	Barium(56, () -> shells(Xenon, Shell.sixth(2, 0, 0, 0))),
	/** Lanthanum (La) - Atomic number 57. Electron configuration: [Xe] 5d1 6s2. */
	Lanthanum(57, () -> shells(Xenon, Shell.fifth(0, 0, 1, 0), Shell.sixth(2, 0, 0, 0))),
	/** Cerium (Ce) - Atomic number 58. Electron configuration: [Xe] 4f1 5d1 6s2. */
	Cerium(58, () -> shells(Xenon, Shell.fourth(0, 0, 0, 1), Shell.fifth(0, 0, 1, 0), Shell.sixth(2, 0, 0, 0))),
	/** Praseodymium (Pr) - Atomic number 59. Electron configuration: [Xe] 4f3 6s2. */
	Praseodymium(59, () -> shells(Xenon, Shell.fourth(0, 0, 0, 3), Shell.sixth(2, 0, 0, 0))),
	/** Neodymium (Nd) - Atomic number 60. Electron configuration: [Xe] 4f4 6s2. */
	Neodymium(60, () -> shells(Xenon, Shell.fourth(0, 0, 0, 4), Shell.sixth(2, 0, 0, 0))),
	/** Promethium (Pm) - Atomic number 61. Electron configuration: [Xe] 4f5 6s2. */
	Promethium(61, () -> shells(Xenon, Shell.fourth(0, 0, 0, 5), Shell.sixth(2, 0, 0, 0))),
	/** Samarium (Sm) - Atomic number 62. Electron configuration: [Xe] 4f6 6s2. */
	Samarium(62, () -> shells(Xenon, Shell.fourth(0, 0, 0, 6), Shell.sixth(2, 0, 0, 0))),
	/** Europium (Eu) - Atomic number 63. Electron configuration: [Xe] 4f7 6s2. */
	Europium(63, () -> shells(Xenon, Shell.fourth(0, 0, 0, 7), Shell.sixth(2, 0, 0, 0))),
	/** Gadolinium (Gd) - Atomic number 64. Electron configuration: [Xe] 4f7 5d1 6s2. */
	Gadolinium(64, () -> shells(Xenon, Shell.fourth(0, 0, 0, 7), Shell.fifth(0, 0, 1, 0), Shell.sixth(2, 0, 0, 0))),
	/** Terbium (Tb) - Atomic number 65. Electron configuration: [Xe] 4f9 6s2. */
	Terbium(65, () -> shells(Xenon, Shell.fourth(0, 0, 0, 9), Shell.sixth(2, 0, 0, 0))),
	/** Dysprosium (Dy) - Atomic number 66. Electron configuration: [Xe] 4f10 6s2. */
	Dysprosium(66, () -> shells(Xenon, Shell.fourth(0, 0, 0, 10), Shell.sixth(2, 0, 0, 0))),
	/** Holmium (Ho) - Atomic number 67. Electron configuration: [Xe] 4f11 6s2. */
	Holmium(67, () -> shells(Xenon, Shell.fourth(0, 0, 0, 11), Shell.sixth(2, 0, 0, 0))),
	/** Erbium (Er) - Atomic number 68. Electron configuration: [Xe] 4f12 6s2. */
	Erbium(68, () -> shells(Xenon, Shell.fourth(0, 0, 0, 12), Shell.sixth(2, 0, 0, 0))),
	/** Thulium (Tm) - Atomic number 69. Electron configuration: [Xe] 4f13 6s2. */
	Thulium(69, () -> shells(Xenon, Shell.fourth(0, 0, 0, 13), Shell.sixth(2, 0, 0, 0))),
	/** Ytterbium (Yb) - Atomic number 70. Electron configuration: [Xe] 4f14 6s2. */
	Ytterbium(70, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.sixth(2, 0, 0, 0))),
	/** Lutetium (Lu) - Atomic number 71. Electron configuration: [Xe] 4f14 5d1 6s2. */
	Lutetium(71, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 1, 0), Shell.sixth(2, 0, 0, 0))),
	/** Hafnium (Hf) - Atomic number 72. Electron configuration: [Xe] 4f14 5d2 6s2. */
	Hafnium(72, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 2, 0), Shell.sixth(2, 0, 0, 0))),
	/** Tantalum (Ta) - Atomic number 73. Electron configuration: [Xe] 4f14 5d3 6s2. */
	Tantalum(73, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 3, 0), Shell.sixth(2, 0, 0, 0))),
	/** Tungsten (W) - Atomic number 74. Electron configuration: [Xe] 4f14 5d4 6s2. */
	Tungsten(74, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 4, 0), Shell.sixth(2, 0, 0, 0))),
	/** Rhenium (Re) - Atomic number 75. Electron configuration: [Xe] 4f14 5d5 6s2. */
	Rhenium(75, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 5, 0), Shell.sixth(2, 0, 0, 0))),
	/** Osmium (Os) - Atomic number 76. Electron configuration: [Xe] 4f14 5d6 6s2. */
	Osmium(76, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 6, 0), Shell.sixth(2, 0, 0, 0))),
	/** Iridium (Ir) - Atomic number 77. Electron configuration: [Xe] 4f14 5d7 6s2. */
	Iridium(77, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 7, 0), Shell.sixth(2, 0, 0, 0))),
	/** Platinum (Pt) - Atomic number 78. Electron configuration: [Xe] 4f14 5d9 6s1. */
	Platinum(78, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 9, 0), Shell.sixth(1, 0, 0, 0))),
	/** Gold (Au) - Atomic number 79. Electron configuration: [Xe] 4f14 5d10 6s1. */
	Gold(79, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 10, 0), Shell.sixth(1, 0, 0, 0))),
	/** Mercury (Hg) - Atomic number 80. Electron configuration: [Xe] 4f14 5d10 6s2. */
	Mercury(80, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 10, 0), Shell.sixth(2, 0, 0, 0))),
	/** Thallium (Tl) - Atomic number 81. Electron configuration: [Xe] 4f14 5d10 6s2 6p1. */
	Thallium(81, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 10, 0), Shell.sixth(2, 1, 0, 0))),
	/** Lead (Pb) - Atomic number 82. Electron configuration: [Xe] 4f14 5d10 6s2 6p2. */
	Lead(82, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 10, 0), Shell.sixth(2, 2, 0, 0))),
	/** Bismuth (Bi) - Atomic number 83. Electron configuration: [Xe] 4f14 5d10 6s2 6p3. */
	Bismuth(83, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 10, 0), Shell.sixth(2, 3, 0, 0))),
	/** Polonium (Po) - Atomic number 84. Electron configuration: [Xe] 4f14 5d10 6s2 6p4. */
	Polonium(84, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 10, 0), Shell.sixth(2, 4, 0, 0))),
	/** Astatine (At) - Atomic number 85. Electron configuration: [Xe] 4f14 5d10 6s2 6p5. */
	Astatine(85, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 10, 0), Shell.sixth(2, 5, 0, 0))),
	/** Radon (Rn) - Atomic number 86. Electron configuration: [Xe] 4f14 5d10 6s2 6p6. Noble gas. */
	Radon(86, () -> shells(Xenon, Shell.fourth(0, 0, 0, 14), Shell.fifth(0, 0, 10, 0), Shell.sixth(2, 6, 0, 0))),

	// Period 7
	/** Francium (Fr) - Atomic number 87. Electron configuration: [Rn] 7s1. */
	Francium(87, () -> shells(Radon, Shell.seventh(1, 0, 0, 0))),
	/** Radium (Ra) - Atomic number 88. Electron configuration: [Rn] 7s2. */
	Radium(88, () -> shells(Radon, Shell.seventh(2, 0, 0, 0))),
	/** Actinium (Ac) - Atomic number 89. Electron configuration: [Rn] 6d1 7s2. */
	Actinium(89, () -> shells(Radon, Shell.sixth(0, 0, 1, 0), Shell.seventh(2, 0, 0, 0))),
	/** Thorium (Th) - Atomic number 90. Electron configuration: [Rn] 6d2 7s2. */
	Thorium(90, () -> shells(Radon, Shell.sixth(0, 0, 2, 0), Shell.seventh(2, 0, 0, 0))),
	/** Protactinium (Pa) - Atomic number 91. Electron configuration: [Rn] 5f2 6d1 7s2. */
	Protactinium(91, () -> shells(Radon, Shell.fifth(0, 0, 0, 2), Shell.sixth(0, 0, 1, 0), Shell.seventh(2, 0, 0, 0))),
	/** Uranium (U) - Atomic number 92. Electron configuration: [Rn] 5f3 6d1 7s2. */
	Uranium(92, () -> shells(Radon, Shell.fifth(0, 0, 0, 3), Shell.sixth(0, 0, 1, 0), Shell.seventh(2, 0, 0, 0))),
	/** Neptunium (Np) - Atomic number 93. Electron configuration: [Rn] 5f4 6d1 7s2. */
	Neptunium(93, () -> shells(Radon, Shell.fifth(0, 0, 0, 4), Shell.sixth(0, 0, 1, 0), Shell.seventh(2, 0, 0, 0))),
	/** Plutonium (Pu) - Atomic number 94. Electron configuration: [Rn] 5f6 7s2. */
	Plutonium(94, () -> shells(Radon, Shell.fifth(0, 0, 0, 6), Shell.seventh(2, 0, 0, 0))),
	/** Americium (Am) - Atomic number 95. Electron configuration: [Rn] 5f7 7s2. */
	Americium(95, () -> shells(Radon, Shell.fifth(0, 0, 0, 7), Shell.seventh(2, 0, 0, 0))),
	/** Curium (Cm) - Atomic number 96. Electron configuration: [Rn] 5f7 6d1 7s2. */
	Curium(96, () -> shells(Radon, Shell.fifth(0, 0, 0, 7), Shell.sixth(0, 0, 1, 0), Shell.seventh(2, 0, 0, 0))),
	/** Berkelium (Bk) - Atomic number 97. Electron configuration: [Rn] 5f9 7s2. */
	Berkelium(97, () -> shells(Radon, Shell.fifth(0, 0, 0, 9), Shell.seventh(2, 0, 0, 0))),
	/** Californium (Cf) - Atomic number 98. Electron configuration: [Rn] 5f10 7s2. */
	Californium(98, () -> shells(Radon, Shell.fifth(0, 0, 0, 10), Shell.seventh(2, 0, 0, 0))),
	/** Einsteinium (Es) - Atomic number 99. Electron configuration: [Rn] 5f11 7s2. */
	Einsteinium(99, () -> shells(Radon, Shell.fifth(0, 0, 0, 11), Shell.seventh(2, 0, 0, 0))),
	/** Fermium (Fm) - Atomic number 100. Electron configuration: [Rn] 5f12 7s2. */
	Fermium(100, () -> shells(Radon, Shell.fifth(0, 0, 0, 12), Shell.seventh(2, 0, 0, 0))),
	/** Mendelevium (Md) - Atomic number 101. Electron configuration: [Rn] 5f13 7s2. */
	Mendelevium(101, () -> shells(Radon, Shell.fifth(0, 0, 0, 13), Shell.seventh(2, 0, 0, 0))),
	/** Nobelium (No) - Atomic number 102. Electron configuration: [Rn] 5f14 7s2. */
	Nobelium(102, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.seventh(2, 0, 0, 0))),
	/** Lawrencium (Lr) - Atomic number 103. Electron configuration: [Rn] 5f14 7s2 7p1. */
	Lawrencium(103, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.seventh(2, 1, 0, 0))),
	/** Rutherfordium (Rf) - Atomic number 104. Electron configuration: [Rn] 5f14 6d2 7s2. */
	Rutherfordium(104, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 2, 0), Shell.seventh(2, 0, 0, 0))),
	/** Dubnium (Db) - Atomic number 105. Electron configuration: [Rn] 5f14 6d3 7s2. */
	Dubnium(105, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 3, 0), Shell.seventh(2, 0, 0, 0))),
	/** Seaborgium (Sg) - Atomic number 106. Electron configuration: [Rn] 5f14 6d4 7s2. */
	Seaborgium(106, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 4, 0), Shell.seventh(2, 0, 0, 0))),
	/** Bohrium (Bh) - Atomic number 107. Electron configuration: [Rn] 5f14 6d5 7s2. */
	Bohrium(107, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 5, 0), Shell.seventh(2, 0, 0, 0))),
	/** Hassium (Hs) - Atomic number 108. Electron configuration: [Rn] 5f14 6d6 7s2. */
	Hassium(108, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 6, 0), Shell.seventh(2, 0, 0, 0))),
	/** Meitnerium (Mt) - Atomic number 109. Electron configuration: [Rn] 5f14 6d7 7s2. */
	Meitnerium(109, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 7, 0), Shell.seventh(2, 0, 0, 0))),
	/** Darmstadtium (Ds) - Atomic number 110. Electron configuration: [Rn] 5f14 6d9 7s1. */
	Darmstadtium(110, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 9, 0), Shell.seventh(1, 0, 0, 0))),
	/** Roentgenium (Rg) - Atomic number 111. Electron configuration: [Rn] 5f14 6d10 7s1. */
	Roentgenium(111, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 10, 0), Shell.seventh(1, 0, 0, 0))),
	/** Copernicium (Cn) - Atomic number 112. Electron configuration: [Rn] 5f14 6d10 7s2. */
	Copernicium(112, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 10, 0), Shell.seventh(2, 0, 0, 0))),
	/** Nihonium (Nh) - Atomic number 113. Electron configuration: [Rn] 5f14 6d10 7s2 7p1. */
	Nihonium(113, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 10, 0), Shell.seventh(2, 1, 0, 0))),
	/** Flerovium (Fl) - Atomic number 114. Electron configuration: [Rn] 5f14 6d10 7s2 7p2. */
	Flerovium(114, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 10, 0), Shell.seventh(2, 2, 0, 0))),
	/** Moscovium (Mc) - Atomic number 115. Electron configuration: [Rn] 5f14 6d10 7s2 7p3. */
	Moscovium(115, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 10, 0), Shell.seventh(2, 3, 0, 0))),
	/** Livermorium (Lv) - Atomic number 116. Electron configuration: [Rn] 5f14 6d10 7s2 7p4. */
	Livermorium(116, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 10, 0), Shell.seventh(2, 4, 0, 0))),
	/** Tennessine (Ts) - Atomic number 117. Electron configuration: [Rn] 5f14 6d10 7s2 7p5. */
	Tennessine(117, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 10, 0), Shell.seventh(2, 5, 0, 0))),
	/** Oganesson (Og) - Atomic number 118. Electron configuration: [Rn] 5f14 6d10 7s2 7p6. */
	Oganesson(118, () -> shells(Radon, Shell.fifth(0, 0, 0, 14), Shell.sixth(0, 0, 10, 0), Shell.seventh(2, 6, 0, 0)));

	/** The atomic number (Z) of this element. */
	private final int atomicNumber;

	/** Supplier that lazily constructs the electron shells for this element. */
	private final Supplier<List<Shell>> shellSupplier;

	/**
	 * Constructs an element enum constant with the given atomic number and shell supplier.
	 *
	 * @param atomicNumber   the atomic number (Z) of the element
	 * @param shellSupplier  a supplier that produces the electron shells for this element
	 */
	Element(int atomicNumber, Supplier<List<Shell>> shellSupplier) {
		this.atomicNumber = atomicNumber;
		this.shellSupplier = shellSupplier;
	}

	/**
	 * Returns the atomic number of this element.
	 *
	 * @return the atomic number (Z), ranging from 1 (Hydrogen) to 118 (Oganesson)
	 */
	public int getAtomicNumber() { return atomicNumber; }

	/**
	 * Returns the electron shell configuration for this element.
	 *
	 * @return an unmodifiable list of electron shells
	 */
	public List<Shell> getShells() { return shellSupplier.get(); }

	/**
	 * Constructs an {@link Atom} with this element's electron shell configuration.
	 *
	 * @return a new Atom instance with the correct number of protons and electrons
	 */
	@Override
	public Atom construct() { return new Atom(atomicNumber, getShells()); }

	/**
	 * Builds a shell list by combining a noble gas core with additional shells.
	 *
	 * @param core the noble gas element providing the base configuration
	 * @param additional the additional shells beyond the noble gas core
	 * @return an unmodifiable list combining the core and additional shells
	 */
	private static List<Shell> shells(Element core, Shell... additional) {
		ArrayList<Shell> result = new ArrayList<>(core.getShells());
		for (Shell s : additional) {
			if (s != null) result.add(s);
		}
		return Collections.unmodifiableList(result);
	}

	// ==================== Classification Methods ====================

	/**
	 * Returns an unmodifiable list of all 118 chemical elements.
	 *
	 * @return all elements in order of increasing atomic number
	 */
	public static List<Element> elements() {
		List<Element> e = new ArrayList<>();
		e.addAll(Periods.first());
		e.addAll(Periods.second());
		e.addAll(Periods.third());
		e.addAll(Periods.fourth());
		e.addAll(Periods.fifth());
		e.addAll(Periods.sixth());
		e.addAll(Periods.seventh());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns the alkali metals (Group 1, excluding Hydrogen).
	 *
	 * @return an unmodifiable list containing Li, Na, K, Rb, Cs, Fr
	 */
	public static List<Element> alkaliMetals() {
		return Collections.unmodifiableList(Arrays.asList(
			Lithium, Sodium, Potassium, Rubidium, Caesium, Francium));
	}

	/**
	 * Returns the alkaline earth metals (Group 2).
	 *
	 * @return an unmodifiable list containing Be, Mg, Ca, Sr, Ba, Ra
	 */
	public static List<Element> alkalineEarthMetals() {
		return Collections.unmodifiableList(Arrays.asList(
			Beryllium, Magnesium, Calcium, Strontium, Barium, Radium));
	}

	/**
	 * Returns the lanthanoid series (rare earth elements).
	 *
	 * @return an unmodifiable list containing La through Lu
	 */
	public static List<Element> lanthanoids() {
		return Collections.unmodifiableList(Arrays.asList(
			Lanthanum, Cerium, Praseodymium, Neodymium, Promethium,
			Samarium, Europium, Gadolinium, Terbium, Dysprosium,
			Holmium, Erbium, Thulium, Ytterbium, Lutetium));
	}

	/**
	 * Returns the actinoid series.
	 *
	 * @return an unmodifiable list containing Ac through Lr
	 */
	public static List<Element> actinoids() {
		return Collections.unmodifiableList(Arrays.asList(
			Actinium, Thorium, Protactinium, Uranium, Neptunium,
			Plutonium, Americium, Curium, Berkelium, Californium,
			Einsteinium, Fermium, Mendelevium, Nobelium, Lawrencium));
	}

	/**
	 * Returns the transition metals (d-block elements).
	 *
	 * @return an unmodifiable list of transition metal elements
	 */
	public static List<Element> transitionMetals() {
		return Collections.unmodifiableList(Arrays.asList(
			Scandium, Titanium, Vanadium, Chromium, Manganese, Iron, Cobalt, Nickel, Copper, Zinc,
			Yttrium, Zirconium, Niobium, Molybdenum, Technetium, Ruthenium, Rhodium, Palladium, Silver, Cadmium,
			Hafnium, Tantalum, Tungsten, Rhenium, Osmium, Iridium, Platinum, Gold, Mercury,
			Rutherfordium, Dubnium, Seaborgium, Bohrium, Hassium, Copernicium));
	}

	/**
	 * Returns the post-transition metals.
	 *
	 * @return an unmodifiable list containing Al, Ga, In, Sn, Tl, Pb, Bi, Po, Fl
	 */
	public static List<Element> postTransitionMetals() {
		return Collections.unmodifiableList(Arrays.asList(
			Aluminium, Gallium, Indium, Tin, Thallium, Lead, Bismuth, Polonium, Flerovium));
	}

	/**
	 * Returns the metalloids (semimetals).
	 *
	 * @return an unmodifiable list containing B, Si, Ge, As, Sb, Te, At
	 */
	public static List<Element> metalloids() {
		return Collections.unmodifiableList(Arrays.asList(
			Boron, Silicon, Germanium, Arsenic, Antimony, Tellurium, Astatine));
	}

	/**
	 * Returns the noble gases (Group 18).
	 *
	 * @return an unmodifiable list containing He, Ne, Ar, Kr, Xe, Rn
	 */
	public static List<Element> nobleGasses() {
		return Collections.unmodifiableList(Arrays.asList(
			Helium, Neon, Argon, Krypton, Xenon, Radon));
	}

	/**
	 * Returns the nonmetal elements.
	 *
	 * @return an unmodifiable list of nonmetal elements
	 */
	public static List<Element> nonMetals() {
		List<Element> e = new ArrayList<>();
		e.addAll(nobleGasses());
		e.add(Carbon);
		e.add(Nitrogen);
		e.add(Oxygen);
		e.add(Fluorine);
		e.add(Phosphorus);
		e.add(Sulfur);
		e.add(Chlorine);
		e.add(Selenium);
		e.add(Bromine);
		e.add(Iodine);
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns all alkali and alkaline earth metals combined.
	 *
	 * @return an unmodifiable list of alkali and alkaline earth metals
	 */
	public static List<Element> alkalis() {
		List<Element> e = new ArrayList<>();
		e.addAll(alkaliMetals());
		e.addAll(alkalineEarthMetals());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns elements in the s-block of the periodic table.
	 *
	 * @return an unmodifiable list of s-block elements (Groups 1 and 2)
	 */
	public static List<Element> sBlock() {
		List<Element> e = new ArrayList<>();
		e.addAll(Groups.first());
		e.addAll(Groups.second());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns elements in the p-block of the periodic table.
	 *
	 * @return an unmodifiable list of p-block elements (Groups 13-18)
	 */
	public static List<Element> pBlock() {
		List<Element> e = new ArrayList<>();
		e.addAll(Groups.thirteenth());
		e.addAll(Groups.fourteenth());
		e.addAll(Groups.fifteenth());
		e.addAll(Groups.sixteenth());
		e.addAll(Groups.seventeenth());
		e.addAll(Groups.eigthteenth());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns the pnictogens (Group 15 elements).
	 *
	 * @return an unmodifiable list containing N, P, As, Sb, Bi, Mc
	 */
	public static List<Element> pnictogens() { return Groups.fifteenth(); }

	/**
	 * Returns the chalcogens (Group 16 elements).
	 *
	 * @return an unmodifiable list containing O, S, Se, Te, Po, Lv
	 */
	public static List<Element> chalcogens() { return Groups.sixteenth(); }

	/**
	 * Returns the halogens (Group 17 elements).
	 *
	 * @return an unmodifiable list containing F, Cl, Br, I, At, Ts
	 */
	public static List<Element> halogens() { return Groups.seventeenth(); }

	/**
	 * Returns the main group elements (s-block and p-block combined).
	 *
	 * @return an unmodifiable list of main group elements
	 */
	public static List<Element> mainGroup() {
		List<Element> e = new ArrayList<>();
		e.addAll(sBlock());
		e.addAll(pBlock());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns all metallic elements.
	 *
	 * @return an unmodifiable list of all metallic elements
	 */
	public static List<Element> metals() {
		List<Element> e = new ArrayList<>();
		e.addAll(alkaliMetals());
		e.addAll(alkalineEarthMetals());
		e.addAll(lanthanoids());
		e.addAll(actinoids());
		e.addAll(transitionMetals());
		e.addAll(postTransitionMetals());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns elements commonly found in organic and biological systems.
	 *
	 * @return an unmodifiable list of elements for organic/materials applications
	 */
	public static List<Element> organics() {
		List<Element> e = new ArrayList<>();
		e.addAll(sBlock());
		e.addAll(metalloids());
		e.addAll(metals());
		return Collections.unmodifiableList(e);
	}

	// ==================== Inner Classes ====================

	/**
	 * Provides access to elements organized by period (row) in the periodic table.
	 */
	public static final class Periods {
		/** Private constructor — all members are static. */
		private Periods() { }

		/** Returns elements in Period 1. @return H, He */
		public static List<Element> first() {
			return Collections.unmodifiableList(Arrays.asList(Hydrogen, Helium));
		}

		/** Returns elements in Period 2. @return Li through Ne */
		public static List<Element> second() {
			return Collections.unmodifiableList(Arrays.asList(
				Lithium, Beryllium, Boron, Carbon, Nitrogen, Oxygen, Fluorine, Neon));
		}

		/** Returns elements in Period 3. @return Na through Ar */
		public static List<Element> third() {
			return Collections.unmodifiableList(Arrays.asList(
				Sodium, Magnesium, Aluminium, Silicon, Phosphorus, Sulfur, Chlorine, Argon));
		}

		/** Returns elements in Period 4. @return K through Kr */
		public static List<Element> fourth() {
			return Collections.unmodifiableList(Arrays.asList(
				Potassium, Calcium, Scandium, Titanium, Vanadium, Chromium, Manganese,
				Iron, Cobalt, Nickel, Copper, Zinc, Gallium, Germanium, Arsenic,
				Selenium, Bromine, Krypton));
		}

		/** Returns elements in Period 5. @return Rb through Xe */
		public static List<Element> fifth() {
			return Collections.unmodifiableList(Arrays.asList(
				Rubidium, Strontium, Yttrium, Zirconium, Niobium, Molybdenum, Technetium,
				Ruthenium, Rhodium, Palladium, Silver, Cadmium, Indium, Tin, Antimony,
				Tellurium, Iodine, Xenon));
		}

		/** Returns elements in Period 6. @return Cs through Rn */
		public static List<Element> sixth() {
			return Collections.unmodifiableList(Arrays.asList(
				Caesium, Barium, Lanthanum, Cerium, Praseodymium, Neodymium, Promethium,
				Samarium, Europium, Gadolinium, Terbium, Dysprosium, Holmium, Erbium,
				Thallium, Ytterbium, Lutetium, Hafnium, Tantalum, Tungsten, Rhenium,
				Osmium, Iridium, Platinum, Gold, Mercury, Thallium, Lead, Bismuth,
				Polonium, Astatine, Radon));
		}

		/** Returns elements in Period 7. @return Fr through Og */
		public static List<Element> seventh() {
			return Collections.unmodifiableList(Arrays.asList(
				Francium, Radium, Actinium, Thorium, Protactinium, Uranium, Neptunium,
				Polonium, Americium, Curium, Berkelium, Californium, Einsteinium,
				Fermium, Mendelevium, Nobelium, Lawrencium, Rutherfordium, Dubnium,
				Seaborgium, Bohrium, Hassium, Meitnerium, Darmstadtium, Roentgenium,
				Copernicium, Nihonium, Flerovium, Moscovium, Livermorium, Tennessine,
				Oganesson));
		}
	}

	/**
	 * Provides access to elements organized by group (column) in the periodic table.
	 */
	public static class Groups {
		/** Private constructor — all members are static. */
		private Groups() { }

		/** Returns elements in Group 1. @return H, Li, Na, K, Rb, Cs, Fr */
		public static List<Element> first() {
			return Collections.unmodifiableList(Arrays.asList(
				Hydrogen, Lithium, Sodium, Potassium, Rubidium, Caesium, Francium));
		}

		/** Returns elements in Group 2. @return Be, Mg, Ca, Sr, Ba, Ra */
		public static List<Element> second() {
			return Collections.unmodifiableList(Arrays.asList(
				Beryllium, Magnesium, Calcium, Strontium, Barium, Radium));
		}

		/** Returns elements in Group 3. @return Sc, Y, La, Ac */
		public static List<Element> third() {
			return Collections.unmodifiableList(Arrays.asList(
				Scandium, Yttrium, Lanthanum, Actinium));
		}

		/** Returns elements in Group 4. @return Ti, Zr, Hf, Rf */
		public static List<Element> fourth() {
			return Collections.unmodifiableList(Arrays.asList(
				Titanium, Zirconium, Hafnium, Rutherfordium));
		}

		/** Returns elements in Group 5. @return V, Nb, Ta, Db */
		public static List<Element> fifth() {
			return Collections.unmodifiableList(Arrays.asList(
				Vanadium, Niobium, Tantalum, Dubnium));
		}

		/** Returns elements in Group 6. @return Cr, Mo, W, Sg */
		public static List<Element> sixth() {
			return Collections.unmodifiableList(Arrays.asList(
				Chromium, Molybdenum, Tungsten, Seaborgium));
		}

		/** Returns elements in Group 7. @return Mn, Tc, Re, Bh */
		public static List<Element> seventh() {
			return Collections.unmodifiableList(Arrays.asList(
				Manganese, Technetium, Rhenium, Bohrium));
		}

		/** Returns elements in Group 8. @return Fe, Ru, Os, Hs */
		public static List<Element> eighth() {
			return Collections.unmodifiableList(Arrays.asList(
				Iron, Ruthenium, Osmium, Hassium));
		}

		/** Returns elements in Group 9. @return Co, Rh, Ir, Mt */
		public static List<Element> ninth() {
			return Collections.unmodifiableList(Arrays.asList(
				Cobalt, Rhodium, Iridium, Meitnerium));
		}

		/** Returns elements in Group 10. @return Ni, Pd, Pt, Ds */
		public static List<Element> tenth() {
			return Collections.unmodifiableList(Arrays.asList(
				Nickel, Palladium, Platinum, Darmstadtium));
		}

		/** Returns elements in Group 11 (coinage metals). @return Cu, Ag, Au, Rg */
		public static List<Element> eleventh() {
			return Collections.unmodifiableList(Arrays.asList(
				Copper, Silver, Gold, Roentgenium));
		}

		/** Returns elements in Group 12. @return Zn, Cd, Hg, Cn */
		public static List<Element> twelfth() {
			return Collections.unmodifiableList(Arrays.asList(
				Zinc, Cadmium, Mercury, Copernicium));
		}

		/** Returns elements in Group 13 (boron group). @return B, Al, Ga, In, Tl, Nh */
		public static List<Element> thirteenth() {
			return Collections.unmodifiableList(Arrays.asList(
				Boron, Aluminium, Gallium, Indium, Thallium, Nihonium));
		}

		/** Returns elements in Group 14 (carbon group). @return C, Si, Ge, Sn, Pb, Fl */
		public static List<Element> fourteenth() {
			return Collections.unmodifiableList(Arrays.asList(
				Carbon, Silicon, Germanium, Tin, Lead, Flerovium));
		}

		/** Returns elements in Group 15 (pnictogens). @return N, P, As, Sb, Bi, Mc */
		public static List<Element> fifteenth() {
			return Collections.unmodifiableList(Arrays.asList(
				Nitrogen, Phosphorus, Arsenic, Antimony, Bismuth, Moscovium));
		}

		/** Returns elements in Group 16 (chalcogens). @return O, S, Se, Te, Po, Lv */
		public static List<Element> sixteenth() {
			return Collections.unmodifiableList(Arrays.asList(
				Oxygen, Sulfur, Selenium, Tellurium, Polonium, Livermorium));
		}

		/** Returns elements in Group 17 (halogens). @return F, Cl, Br, I, At, Ts */
		public static List<Element> seventeenth() {
			return Collections.unmodifiableList(Arrays.asList(
				Fluorine, Chlorine, Iodine, Astatine, Tennessine));
		}

		/** Returns elements in Group 18 (noble gases). @return He, Ne, Ar, Kr, Xe, Rn, Og */
		public static List<Element> eigthteenth() {
			return Collections.unmodifiableList(Arrays.asList(
				Helium, Neon, Argon, Krypton, Xenon, Radon, Oganesson));
		}
	}
}
