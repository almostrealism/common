package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Provides access to all 118 chemical elements of the periodic table.
 *
 * <p>The {@code PeriodicTable} class is the central access point for chemical elements
 * in the chemistry module. It provides:</p>
 * <ul>
 *   <li>Static constants for all 118 elements (Hydrogen through Oganesson)</li>
 *   <li>Methods to retrieve elements by chemical family (alkali metals, noble gases, etc.)</li>
 *   <li>Methods to retrieve elements by period (row) or group (column)</li>
 *   <li>Methods to retrieve elements by orbital block (s-block, p-block, etc.)</li>
 * </ul>
 *
 * <h2>Accessing Individual Elements</h2>
 * <pre>{@code
 * Element hydrogen = PeriodicTable.Hydrogen;
 * Element carbon = PeriodicTable.Carbon;
 * Element gold = PeriodicTable.Gold;
 *
 * // Construct atoms
 * Atom carbonAtom = carbon.construct();
 * }</pre>
 *
 * <h2>Accessing Element Groups</h2>
 * <pre>{@code
 * // By chemical family
 * List<Element> alkaliMetals = PeriodicTable.alkaliMetals();     // Li, Na, K, Rb, Cs, Fr
 * List<Element> nobleGases = PeriodicTable.nobleGasses();        // He, Ne, Ar, Kr, Xe, Rn
 * List<Element> halogens = PeriodicTable.halogens();             // F, Cl, Br, I, At
 * List<Element> transitionMetals = PeriodicTable.transitionMetals();
 *
 * // By orbital block
 * List<Element> sBlock = PeriodicTable.sBlock();  // Groups 1-2
 * List<Element> pBlock = PeriodicTable.pBlock();  // Groups 13-18
 * }</pre>
 *
 * <h2>Accessing by Period or Group</h2>
 * <pre>{@code
 * // By period (row)
 * List<Element> period1 = PeriodicTable.Periods.first();   // H, He
 * List<Element> period2 = PeriodicTable.Periods.second();  // Li through Ne
 *
 * // By group (column)
 * List<Element> group1 = PeriodicTable.Groups.first();      // H, Li, Na, K, Rb, Cs, Fr
 * List<Element> group18 = PeriodicTable.Groups.eigthteenth(); // Noble gases
 * }</pre>
 *
 * <h2>Periodic Table Organization</h2>
 * <p>The elements are organized into 7 periods (rows) and 18 groups (columns):</p>
 * <ul>
 *   <li><b>Period 1:</b> H, He (2 elements)</li>
 *   <li><b>Period 2:</b> Li through Ne (8 elements)</li>
 *   <li><b>Period 3:</b> Na through Ar (8 elements)</li>
 *   <li><b>Period 4:</b> K through Kr (18 elements)</li>
 *   <li><b>Period 5:</b> Rb through Xe (18 elements)</li>
 *   <li><b>Period 6:</b> Cs through Rn (32 elements, including lanthanides)</li>
 *   <li><b>Period 7:</b> Fr through Og (32 elements, including actinides)</li>
 * </ul>
 *
 * @see Element
 * @see Atomic
 * @see Alloy
 *
 * @author Michael Murray
 */
public final class PeriodicTable {
	// Period 1
	public static final Hydrogen Hydrogen = new Hydrogen();
	public static final Helium Helium = new Helium();
	
	// Period 2
	public static final Lithium Lithium = new Lithium();
	public static final Beryllium Beryllium = new Beryllium();
	public static final Boron Boron = new Boron();
	public static final Carbon Carbon = new Carbon();
	public static final Nitrogen Nitrogen = new Nitrogen();
	public static final Oxygen Oxygen = new Oxygen();
	public static final Fluorine Fluorine = new Fluorine();
	public static final Neon Neon = new Neon();
	
	// Period 3
	public static final Sodium Sodium = new Sodium();
	public static final Magnesium Magnesium = new Magnesium();
	public static final Aluminium Aluminium = new Aluminium();
	public static final Silicon Silicon = new Silicon();
	public static final Phosphorus Phosphorus = new Phosphorus();
	public static final Sulfur Sulfur = new Sulfur();
	public static final Chlorine Chlorine = new Chlorine();
	public static final Argon Argon = new Argon();
	
	// Period 4
	public static final Potassium Potassium = new Potassium();
	public static final Calcium Calcium = new Calcium();
	public static final Scandium Scandium = new Scandium();
	public static final Titanium Titanium = new Titanium();
	public static final Vanadium Vanadium = new Vanadium();
	public static final Chromium Chromium = new Chromium();
	public static final Manganese Manganese = new Manganese();
	public static final Iron Iron = new Iron();
	public static final Cobalt Cobalt = new Cobalt();
	public static final Nickel Nickel = new Nickel();
	public static final Copper Copper = new Copper();
	public static final Zinc Zinc = new Zinc();
	public static final Gallium Gallium = new Gallium();
	public static final Germanium Germanium = new Germanium();
	public static final Arsenic Arsenic = new Arsenic();
	public static final Selenium Selenium = new Selenium();
	public static final Bromine Bromine = new Bromine();
	public static final Krypton Krypton = new Krypton();
	
	// Period 5
	public static final Rubidium Rubidium = new Rubidium();
	public static final Strontium Strontium = new Strontium();
	public static final Yttrium Yttrium = new Yttrium();
	public static final Zirconium Zirconium = new Zirconium();
	public static final Niobium Niobium = new Niobium();
	public static final Molybdenum Molybdenum = new Molybdenum();
	public static final Technetium Technetium = new Technetium();
	public static final Ruthenium Ruthenium = new Ruthenium();
	public static final Rhodium Rhodium = new Rhodium();
	public static final Palladium Palladium = new Palladium();
	public static final Silver Silver = new Silver();
	public static final Cadmium Cadmium = new Cadmium();
	public static final Indium Indium = new Indium();
	public static final Tin Tin = new Tin();
	public static final Antimony Antimony = new Antimony();
	public static final Tellurium Tellurium = new Tellurium();
	public static final Iodine Iodine = new Iodine();
	public static final Xenon Xenon = new Xenon();
	
	// Period 6
	public static final Cesium Caesium = new Cesium();
	public static final Barium Barium = new Barium();
	public static final Lanthanum Lanthanum = new Lanthanum();
	public static final Cerium Cerium = new Cerium();
	public static final Praseodymium Praseodymium = new Praseodymium();
	public static final Neodymium Neodymium = new Neodymium();
	public static final Promethium Promethium = new Promethium();
	public static final Samarium Samarium = new Samarium();
	public static final Europium Europium = new Europium();
	public static final Gadolinium Gadolinium = new Gadolinium();
	public static final Terbium Terbium = new Terbium();
	public static final Dysprosium Dysprosium = new Dysprosium();
	public static final Holmium Holmium = new Holmium();
	public static final Erbium Erbium = new Erbium();
	public static final Thulium Thulium = new Thulium();
	public static final Ytterbium Ytterbium = new Ytterbium();
	public static final Lutetium Lutetium = new Lutetium();
	public static final Hafnium Hafnium = new Hafnium();
	public static final Tantalum Tantalum = new Tantalum();
	public static final Tungsten Tungsten = new Tungsten();
	public static final Rhenium Rhenium = new Rhenium();
	public static final Osmium Osmium = new Osmium();
	public static final Iridium Iridium = new Iridium();
	public static final Platinum Platinum = new Platinum();
	public static final Gold Gold = new Gold();
	public static final Mercury Mercury = new Mercury();
	public static final Thallium Thallium = new Thallium();
	public static final Lead Lead = new Lead();
	public static final Bismuth Bismuth = new Bismuth();
	public static final Polonium Polonium = new Polonium();
	public static final Astatine Astatine = new Astatine();
	public static final Radon Radon = new Radon();
	
	// Period 7
	public static final Francium Francium = new Francium();
	public static final Radium Radium = new Radium();
	public static final Actinium Actinium = new Actinium();
	public static final Thorium Thorium = new Thorium();
	public static final Protactinium Protactinium = new Protactinium();
	public static final Uranium Uranium = new Uranium();
	public static final Neptunium Neptunium = new Neptunium();
	public static final Plutonium Plutonium = new Plutonium();
	public static final Americium Americium = new Americium();
	public static final Curium Curium = new Curium();
	public static final Berkelium Berkelium = new Berkelium();
	public static final Californium Californium = new Californium();
	public static final Einsteinium Einsteinium = new Einsteinium();
	public static final Fermium Fermium = new Fermium();
	public static final Mendelevium Mendelevium = new Mendelevium();
	public static final Nobelium Nobelium = new Nobelium();
	public static final Lawrencium Lawrencium = new Lawrencium();
	public static final Rutherfordium Rutherfordium = new Rutherfordium();
	public static final Dubnium Dubnium = new Dubnium();
	public static final Seaborgium Seaborgium = new Seaborgium();
	public static final Bohrium Bohrium = new Bohrium();
	public static final Hassium Hassium = new Hassium();
	public static final Meitnerium Meitnerium = new Meitnerium();
	public static final Darmstadtium Darmstadtium = new Darmstadtium();
	public static final Roentgenium Roentgenium = new Roentgenium();
	public static final Copernicium Copernicium = new Copernicium();
	public static final Nihonium Nihonium = new Nihonium();
	public static final Flerovium Flerovium = new Flerovium();
	public static final Moscovium Moscovium = new Moscovium();
	public static final Livermorium Livermorium = new Livermorium();
	public static final Tennessine Tennessine = new Tennessine();
	public static final Oganesson Oganesson = new Oganesson();
	
	/** Private constructor to prevent instantiation. */
	private PeriodicTable() { }

	/**
	 * Returns an unmodifiable list of all 118 chemical elements.
	 *
	 * <p>Elements are returned in order of increasing atomic number,
	 * from Hydrogen (1) to Oganesson (118).</p>
	 *
	 * @return an unmodifiable list containing all elements in the periodic table
	 */
	public static List<Element> elements() {
		List<Element> e = new ArrayList<Element>();
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
	 * <p>Alkali metals are highly reactive metals that form strong alkaline
	 * hydroxides when combined with water. They have a single valence electron
	 * in their outermost shell.</p>
	 *
	 * @return an unmodifiable list containing Li, Na, K, Rb, Cs, Fr
	 */
	public static List<Element> alkaliMetals() {
		return Collections.unmodifiableList(Arrays.asList(
										Lithium,
										Sodium,
										Potassium,
										Rubidium,
										Caesium,
										Francium));
	}

	/**
	 * Returns the alkaline earth metals (Group 2).
	 *
	 * <p>Alkaline earth metals are reactive metals with two valence electrons.
	 * They form compounds that are important in biological systems and
	 * industrial applications.</p>
	 *
	 * @return an unmodifiable list containing Be, Mg, Ca, Sr, Ba, Ra
	 */
	public static List<Element> alkalineEarthMetals() {
		return Collections.unmodifiableList(Arrays.asList(
										Beryllium,
										Magnesium,
										Calcium,
										Strontium,
										Barium,
										Radium));
	}

	/**
	 * Returns the lanthanoid series (rare earth elements).
	 *
	 * <p>The lanthanoids are the 15 metallic elements from Lanthanum (57) to
	 * Lutetium (71). They are characterized by filling of the 4f electron
	 * subshell and have similar chemical properties. Also known as the
	 * lanthanide series or rare earth elements.</p>
	 *
	 * @return an unmodifiable list containing La through Lu
	 */
	public static List<Element> lanthanoids() {
		return Collections.unmodifiableList(Arrays.asList(
										Lanthanum,
										Cerium,
										Praseodymium,
										Neodymium,
										Promethium,
										Samarium,
										Europium,
										Gadolinium,
										Terbium,
										Dysprosium,
										Holmium,
										Erbium,
										Thulium,
										Ytterbium,
										Lutetium));
	}

	/**
	 * Returns the actinoid series.
	 *
	 * <p>The actinoids are the 15 metallic elements from Actinium (89) to
	 * Lawrencium (103). They are characterized by filling of the 5f electron
	 * subshell. Many actinoids are radioactive, and several (uranium, plutonium)
	 * are important for nuclear energy applications.</p>
	 *
	 * @return an unmodifiable list containing Ac through Lr
	 */
	public static List<Element> actinoids() {
		return Collections.unmodifiableList(Arrays.asList(
										Actinium,
										Thorium,
										Protactinium,
										Uranium,
										Neptunium,
										Plutonium,
										Americium,
										Curium,
										Berkelium,
										Californium,
										Einsteinium,
										Fermium,
										Mendelevium,
										Nobelium,
										Lawrencium));
	}

	/**
	 * Returns the transition metals (d-block elements).
	 *
	 * <p>Transition metals are elements in groups 3-12 that have partially
	 * filled d orbitals. They are characterized by variable oxidation states,
	 * colored compounds, and catalytic properties. This group includes many
	 * industrially important metals like iron, copper, and gold.</p>
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
	 * <p>Post-transition metals are metallic elements located between the
	 * transition metals and the metalloids in the periodic table. They are
	 * softer and have lower melting points than transition metals.</p>
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
	 * <p>Metalloids have properties intermediate between metals and nonmetals.
	 * They are semiconductors and are important in electronics (silicon, germanium).
	 * They form a diagonal band across the periodic table.</p>
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
	 * <p>Noble gases are chemically inert elements with complete valence shells.
	 * They are colorless, odorless, and monatomic gases under standard conditions.
	 * Their low reactivity makes them useful in applications requiring inert
	 * atmospheres.</p>
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
	 * <p>Nonmetals are elements that lack metallic properties. They tend to
	 * have high ionization energies and electronegativity. This group includes
	 * the noble gases plus reactive nonmetals important in organic chemistry
	 * and biological systems.</p>
	 *
	 * @return an unmodifiable list of nonmetal elements
	 */
	public static List<Element> nonMetals() {
		List<Element> e = new ArrayList<Element>();
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
	 * <p>This convenience method combines the alkali metals (Group 1) and
	 * alkaline earth metals (Group 2) into a single list.</p>
	 *
	 * @return an unmodifiable list of alkali and alkaline earth metals
	 * @see #alkaliMetals()
	 * @see #alkalineEarthMetals()
	 */
	public static List<Element> alkalis() {
		List<Element> e = new ArrayList<Element>();
		e.addAll(alkaliMetals());
		e.addAll(alkalineEarthMetals());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns elements in the s-block of the periodic table.
	 *
	 * <p>The s-block consists of Groups 1 and 2 (including Hydrogen and Helium).
	 * These elements have their outermost electrons in s orbitals. The s-block
	 * includes the highly reactive alkali and alkaline earth metals.</p>
	 *
	 * @return an unmodifiable list of s-block elements (Groups 1 and 2)
	 */
	public static List<Element> sBlock() {
		List<Element> e = new ArrayList<Element>();
		e.addAll(Groups.first());
		e.addAll(Groups.second());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns elements in the p-block of the periodic table.
	 *
	 * <p>The p-block consists of Groups 13-18 (excluding Helium). These elements
	 * have their outermost electrons in p orbitals. The p-block contains a
	 * diverse range of elements including nonmetals, metalloids, and metals,
	 * as well as the noble gases.</p>
	 *
	 * @return an unmodifiable list of p-block elements (Groups 13-18)
	 */
	public static List<Element> pBlock() {
		List<Element> e = new ArrayList<Element>();
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
	 * <p>Pnictogens are nitrogen-group elements with five valence electrons.
	 * They include essential biological elements (nitrogen, phosphorus) and
	 * elements with diverse chemical applications.</p>
	 *
	 * @return an unmodifiable list containing N, P, As, Sb, Bi, Mc
	 */
	public static List<Element> pnictogens() { return Groups.fifteenth(); }

	/**
	 * Returns the chalcogens (Group 16 elements).
	 *
	 * <p>Chalcogens are oxygen-group elements with six valence electrons.
	 * They are often found in ores and minerals. Oxygen and sulfur are
	 * essential for life, while selenium and tellurium have important
	 * industrial applications.</p>
	 *
	 * @return an unmodifiable list containing O, S, Se, Te, Po, Lv
	 */
	public static List<Element> chalcogens() { return Groups.sixteenth(); }

	/**
	 * Returns the halogens (Group 17 elements).
	 *
	 * <p>Halogens are highly reactive nonmetals with seven valence electrons.
	 * They readily form salts with metals (halogen means "salt-former").
	 * Chlorine, bromine, and iodine are widely used in disinfection and
	 * organic synthesis.</p>
	 *
	 * @return an unmodifiable list containing F, Cl, Br, I, At, Ts
	 */
	public static List<Element> halogens() { return Groups.seventeenth(); }

	/**
	 * Returns the main group elements (s-block and p-block combined).
	 *
	 * <p>Main group elements are those in Groups 1, 2, and 13-18. They show
	 * predictable trends in their chemical behavior based on their position
	 * in the periodic table, unlike the transition metals.</p>
	 *
	 * @return an unmodifiable list of main group elements
	 * @see #sBlock()
	 * @see #pBlock()
	 */
	public static List<Element> mainGroup() {
		List<Element> e = new ArrayList<Element>();
		e.addAll(sBlock());
		e.addAll(pBlock());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Returns all metallic elements.
	 *
	 * <p>Metals are elements that are typically solid, lustrous, malleable,
	 * ductile, and good conductors of heat and electricity. This method
	 * returns all metals including alkali metals, alkaline earth metals,
	 * lanthanoids, actinoids, transition metals, and post-transition metals.</p>
	 *
	 * @return an unmodifiable list of all metallic elements
	 */
	public static List<Element> metals() {
		List<Element> e = new ArrayList<Element>();
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
	 * <p>This method returns a broad set of elements that participate in
	 * organic chemistry and materials science, including s-block elements,
	 * metalloids, and all metals. This provides a comprehensive set for
	 * materials and compound modeling.</p>
	 *
	 * @return an unmodifiable list of elements for organic/materials applications
	 */
	public static List<Element> organics() {
		List<Element> e = new ArrayList<Element>();
		e.addAll(sBlock());
		e.addAll(metalloids());
		e.addAll(metals());
		return Collections.unmodifiableList(e);
	}

	/**
	 * Provides access to elements organized by period (row) in the periodic table.
	 *
	 * <p>A period is a horizontal row in the periodic table. Elements in the same
	 * period have the same number of electron shells. The periodic table has
	 * 7 periods:</p>
	 * <ul>
	 *   <li>Period 1: 2 elements (H, He)</li>
	 *   <li>Period 2: 8 elements (Li - Ne)</li>
	 *   <li>Period 3: 8 elements (Na - Ar)</li>
	 *   <li>Periods 4-5: 18 elements each</li>
	 *   <li>Periods 6-7: 32 elements each (including lanthanoids/actinoids)</li>
	 * </ul>
	 */
	public static final class Periods {
		/** Private constructor to prevent instantiation. */
		private Periods() { }

		/**
		 * Returns elements in Period 1 (first row).
		 *
		 * @return an unmodifiable list containing Hydrogen and Helium
		 */
		public static List<Element> first() {
			return Collections.unmodifiableList(Arrays.asList(
											Hydrogen,
											Helium));
		}

		/**
		 * Returns elements in Period 2 (second row).
		 *
		 * @return an unmodifiable list containing Li through Ne
		 */
		public static List<Element> second() {
			return Collections.unmodifiableList(Arrays.asList(
											Lithium,
											Beryllium,
											Boron,
											Carbon,
											Nitrogen,
											Oxygen,
											Fluorine,
											Neon));
		}

		/**
		 * Returns elements in Period 3 (third row).
		 *
		 * @return an unmodifiable list containing Na through Ar
		 */
		public static List<Element> third() {
			return Collections.unmodifiableList(Arrays.asList(
											Sodium,
											Magnesium,
											Aluminium,
											Silicon,
											Phosphorus,
											Sulfur,
											Chlorine,
											Argon));
		}

		/**
		 * Returns elements in Period 4 (fourth row).
		 *
		 * @return an unmodifiable list containing K through Kr (18 elements)
		 */
		public static List<Element> fourth() {
			return Collections.unmodifiableList(Arrays.asList(
											Potassium,
											Calcium,
											Scandium,
											Titanium,
											Vanadium,
											Chromium,
											Manganese,
											Iron,
											Cobalt,
											Nickel,
											Copper,
											Zinc,
											Gallium,
											Germanium,
											Arsenic,
											Selenium,
											Bromine,
											Krypton));
		}

		/**
		 * Returns elements in Period 5 (fifth row).
		 *
		 * @return an unmodifiable list containing Rb through Xe (18 elements)
		 */
		public static List<Element> fifth() {
			return Collections.unmodifiableList(Arrays.asList(
											Rubidium,
											Strontium,
											Yttrium,
											Zirconium,
											Niobium,
											Molybdenum,
											Technetium,
											Ruthenium,
											Rhodium,
											Palladium,
											Silver,
											Cadmium,
											Indium,
											Tin,
											Antimony,
											Tellurium,
											Iodine,
											Xenon));
		}

		/**
		 * Returns elements in Period 6 (sixth row).
		 *
		 * <p>This period includes the lanthanoid series (rare earth elements).</p>
		 *
		 * @return an unmodifiable list containing Cs through Rn (32 elements)
		 */
		public static List<Element> sixth() {
			return Collections.unmodifiableList(Arrays.asList(
											Caesium,
											Barium,
											Lanthanum,
											Cerium,
											Praseodymium,
											Neodymium,
											Promethium,
											Samarium,
											Europium,
											Gadolinium,
											Terbium,
											Dysprosium,
											Holmium,
											Erbium,
											Thallium,
											Ytterbium,
											Lutetium,
											Hafnium,
											Tantalum,
											Tungsten,
											Rhenium,
											Osmium,
											Iridium,
											Platinum,
											Gold,
											Mercury,
											Thallium,
											Lead,
											Bismuth,
											Polonium,
											Astatine,
											Radon));
		}

		/**
		 * Returns elements in Period 7 (seventh row).
		 *
		 * <p>This period includes the actinoid series. Many elements in this
		 * period are synthetic and radioactive.</p>
		 *
		 * @return an unmodifiable list containing Fr through Og (32 elements)
		 */
		public static List<Element> seventh() {
			return Collections.unmodifiableList(Arrays.asList(
											Francium,
											Radium,
											Actinium,
											Thorium,
											Protactinium,
											Uranium,
											Neptunium,
											Polonium,
											Americium,
											Curium,
											Berkelium,
											Californium,
											Einsteinium,
											Fermium,
											Mendelevium,
											Nobelium,
											Lawrencium,
											Rutherfordium,
											Dubnium,
											Seaborgium,
											Bohrium,
											Hassium,
											Meitnerium,
											Darmstadtium,
											Roentgenium,
											Copernicium,
											Nihonium,
											Flerovium,
											Moscovium,
											Livermorium,
											Tennessine,
											Oganesson));
		}
	}

	/**
	 * Provides access to elements organized by group (column) in the periodic table.
	 *
	 * <p>A group is a vertical column in the periodic table. Elements in the same
	 * group have similar chemical properties due to having the same number of
	 * valence electrons. The periodic table has 18 groups:</p>
	 * <ul>
	 *   <li>Groups 1-2: s-block elements (alkali and alkaline earth metals)</li>
	 *   <li>Groups 3-12: d-block elements (transition metals)</li>
	 *   <li>Groups 13-18: p-block elements</li>
	 * </ul>
	 */
	public static class Groups {
		/** Private constructor to prevent instantiation. */
		private Groups() { }

		/**
		 * Returns elements in Group 1 (alkali metals + hydrogen).
		 *
		 * @return an unmodifiable list containing H, Li, Na, K, Rb, Cs, Fr
		 */
		public static List<Element> first() {
			return Collections.unmodifiableList(Arrays.asList(
											Hydrogen,
											Lithium,
											Sodium,
											Potassium,
											Rubidium,
											Caesium,
											Francium));
		}

		/**
		 * Returns elements in Group 2 (alkaline earth metals).
		 *
		 * @return an unmodifiable list containing Be, Mg, Ca, Sr, Ba, Ra
		 */
		public static List<Element> second() {
			return Collections.unmodifiableList(Arrays.asList(
											Beryllium,
											Magnesium,
											Calcium,
											Strontium,
											Barium,
											Radium));
		}

		/**
		 * Returns elements in Group 3.
		 *
		 * @return an unmodifiable list containing Sc, Y, La, Ac
		 */
		public static List<Element> third() {
			return Collections.unmodifiableList(Arrays.asList(
											Scandium,
											Yttrium,
											Lanthanum,
											Actinium));
		}

		/** Returns elements in Group 4. @return an unmodifiable list containing Ti, Zr, Hf, Rf */
		public static List<Element> fourth() {
			return Collections.unmodifiableList(Arrays.asList(
											Titanium,
											Zirconium,
											Hafnium,
											Rutherfordium));

		}

		/** Returns elements in Group 5. @return an unmodifiable list containing V, Nb, Ta, Db */
		public static List<Element> fifth() {
			return Collections.unmodifiableList(Arrays.asList(
											Vanadium,
											Niobium,
											Tantalum,
											Dubnium));
		}

		/** Returns elements in Group 6. @return an unmodifiable list containing Cr, Mo, W, Sg */
		public static List<Element> sixth() {
			return Collections.unmodifiableList(Arrays.asList(
											Chromium,
											Molybdenum,
											Tungsten,
											Seaborgium));
		}

		/** Returns elements in Group 7. @return an unmodifiable list containing Mn, Tc, Re, Bh */
		public static List<Element> seventh() {
			return Collections.unmodifiableList(Arrays.asList(
											Manganese,
											Technetium,
											Rhenium,
											Bohrium));
		}

		/** Returns elements in Group 8. @return an unmodifiable list containing Fe, Ru, Os, Hs */
		public static List<Element> eighth() {
			return Collections.unmodifiableList(Arrays.asList(
											Iron,
											Ruthenium,
											Osmium,
											Hassium));
		}

		/** Returns elements in Group 9. @return an unmodifiable list containing Co, Rh, Ir, Mt */
		public static List<Element> ninth() {
			return Collections.unmodifiableList(Arrays.asList(
											Cobalt,
											Rhodium,
											Iridium,
											Meitnerium));
		}

		/** Returns elements in Group 10. @return an unmodifiable list containing Ni, Pd, Pt, Ds */
		public static List<Element> tenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Nickel,
											Palladium,
											Platinum,
											Darmstadtium));
		}

		/** Returns elements in Group 11 (coinage metals). @return an unmodifiable list containing Cu, Ag, Au, Rg */
		public static List<Element> eleventh() {
			return Collections.unmodifiableList(Arrays.asList(
											Copper,
											Silver,
											Gold,
											Roentgenium));
		}

		/** Returns elements in Group 12. @return an unmodifiable list containing Zn, Cd, Hg, Cn */
		public static List<Element> twelfth() {
			return Collections.unmodifiableList(Arrays.asList(
											Zinc,
											Cadmium,
											Mercury,
											Copernicium));
		}

		/** Returns elements in Group 13 (boron group). @return an unmodifiable list containing B, Al, Ga, In, Tl, Nh */
		public static List<Element> thirteenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Boron,
											Aluminium,
											Gallium,
											Indium,
											Thallium,
											Nihonium));
		}

		/** Returns elements in Group 14 (carbon group). @return an unmodifiable list containing C, Si, Ge, Sn, Pb, Fl */
		public static List<Element> fourteenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Carbon,
											Silicon,
											Germanium,
											Tin,
											Lead,
											Flerovium));
		}

		/** Returns elements in Group 15 (pnictogens/nitrogen group). @return an unmodifiable list containing N, P, As, Sb, Bi, Mc */
		public static List<Element> fifteenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Nitrogen,
											Phosphorus,
											Arsenic,
											Antimony,
											Bismuth,
											Moscovium));
		}

		/** Returns elements in Group 16 (chalcogens/oxygen group). @return an unmodifiable list containing O, S, Se, Te, Po, Lv */
		public static List<Element> sixteenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Oxygen,
											Sulfur,
											Selenium,
											Tellurium,
											Polonium,
											Livermorium));
		}

		/** Returns elements in Group 17 (halogens). @return an unmodifiable list containing F, Cl, Br, I, At, Ts */
		public static List<Element> seventeenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Fluorine,
											Chlorine,
											Iodine,
											Astatine,
											Tennessine));
		}

		/** Returns elements in Group 18 (noble gases). @return an unmodifiable list containing He, Ne, Ar, Kr, Xe, Rn, Og */
		public static List<Element> eigthteenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Helium,
											Neon,
											Argon,
											Krypton,
											Xenon,
											Radon,
											Oganesson));
		}
	}
}
