package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
	
	private PeriodicTable() { }
	
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
	
	public static List<Element> alkaliMetals() {
		return Collections.unmodifiableList(Arrays.asList(
										Lithium,
										Sodium,
										Potassium,
										Rubidium,
										Caesium,
										Francium));
	}
	
	public static List<Element> alkalineEarthMetals() {
		return Collections.unmodifiableList(Arrays.asList(
										Beryllium,
										Magnesium,
										Calcium,
										Strontium,
										Barium,
										Radium));
	}
	
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
	
	public static List<Element> transitionMetals() {
		return Collections.unmodifiableList(Arrays.asList(
										Scandium, Titanium, Vanadium, Chromium, Manganese, Iron, Cobalt, Nickel, Copper, Zinc,
										Yttrium, Zirconium, Niobium, Molybdenum, Technetium, Ruthenium, Rhodium, Palladium, Silver, Cadmium,
										Hafnium, Tantalum, Tungsten, Rhenium, Osmium, Iridium, Platinum, Gold, Mercury,
										Rutherfordium, Dubnium, Seaborgium, Bohrium, Hassium, Copernicium));
	}
	
	public static List<Element> postTransitionMetals() {
		return Collections.unmodifiableList(Arrays.asList(
										Aluminium, Gallium, Indium, Tin, Thallium, Lead, Bismuth, Polonium, Flerovium));
	}
	
	public static List<Element> metalloids() {
		return Collections.unmodifiableList(Arrays.asList(
										Boron, Silicon, Germanium, Arsenic, Antimony, Tellurium, Astatine));
	}
	
	public static List<Element> nobleGasses() {
		return Collections.unmodifiableList(Arrays.asList(
										Helium, Neon, Argon, Krypton, Xenon, Radon));
	}
	
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
	
	public static List<Element> alkalis() {
		List<Element> e = new ArrayList<Element>();
		e.addAll(alkaliMetals());
		e.addAll(alkalineEarthMetals());
		return Collections.unmodifiableList(e);
	}
	
	public static List<Element> sBlock() {
		List<Element> e = new ArrayList<Element>();
		e.addAll(Groups.first());
		e.addAll(Groups.second());
		return Collections.unmodifiableList(e);
	}
	
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
	
	public static List<Element> pnictogens() { return Groups.fifteenth(); }
	public static List<Element> chalcogens() { return Groups.sixteenth(); }
	public static List<Element> halogens() { return Groups.seventeenth(); }
	
	public static List<Element> mainGroup() {
		List<Element> e = new ArrayList<Element>();
		e.addAll(sBlock());
		e.addAll(pBlock());
		return Collections.unmodifiableList(e);
	}
	
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
	
	public static List<Element> organics() {
		List<Element> e = new ArrayList<Element>();
		e.addAll(sBlock());
		e.addAll(metalloids());
		e.addAll(metals());
		return Collections.unmodifiableList(e);
	}
	
	public static final class Periods {
		private Periods() { }
		
		public static List<Element> first() {
			return Collections.unmodifiableList(Arrays.asList(
											Hydrogen,
											Helium));
		}
		
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
	
	public static class Groups {
		private Groups() { }
		
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
		
		public static List<Element> second() {
			return Collections.unmodifiableList(Arrays.asList(
											Beryllium,
											Magnesium,
											Calcium,
											Strontium,
											Barium,
											Radium));
		}
		
		public static List<Element> third() {
			return Collections.unmodifiableList(Arrays.asList(
											Scandium,
											Yttrium,
											Lanthanum,
											Actinium));
		}
		
		public static List<Element> fourth() {
			return Collections.unmodifiableList(Arrays.asList(
											Titanium,
											Zirconium,
											Hafnium,
											Rutherfordium));
											
		}
		
		public static List<Element> fifth() {
			return Collections.unmodifiableList(Arrays.asList(
											Vanadium,
											Niobium,
											Tantalum,
											Dubnium));
		}
		
		public static List<Element> sixth() {
			return Collections.unmodifiableList(Arrays.asList(
											Chromium,
											Molybdenum,
											Tungsten,
											Seaborgium));
		}
		
		public static List<Element> seventh() {
			return Collections.unmodifiableList(Arrays.asList(
											Manganese,
											Technetium,
											Rhenium,
											Bohrium));
		}
		
		public static List<Element> eighth() {
			return Collections.unmodifiableList(Arrays.asList(
											Iron,
											Ruthenium,
											Osmium,
											Hassium));
		}
		
		public static List<Element> ninth() {
			return Collections.unmodifiableList(Arrays.asList(
											Cobalt,
											Rhodium,
											Iridium,
											Meitnerium));
		}
		
		public static List<Element> tenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Nickel,
											Palladium,
											Platinum,
											Darmstadtium));
		}
		
		public static List<Element> eleventh() {
			return Collections.unmodifiableList(Arrays.asList(
											Copper,
											Silver,
											Gold,
											Roentgenium));
		}
		
		public static List<Element> twelfth() {
			return Collections.unmodifiableList(Arrays.asList(
											Zinc,
											Cadmium,
											Mercury,
											Copernicium));
		}
		
		public static List<Element> thirteenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Boron,
											Aluminium,
											Gallium,
											Indium,
											Thallium,
											Nihonium));
		}
		
		public static List<Element> fourteenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Carbon,
											Silicon,
											Germanium,
											Tin,
											Lead,
											Flerovium));
		}
		
		public static List<Element> fifteenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Nitrogen,
											Phosphorus,
											Arsenic,
											Antimony,
											Bismuth,
											Moscovium));
		}
		
		public static List<Element> sixteenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Oxygen,
											Sulfur,
											Selenium,
											Tellurium,
											Polonium,
											Livermorium));
		}
		
		public static List<Element> seventeenth() {
			return Collections.unmodifiableList(Arrays.asList(
											Fluorine,
											Chlorine,
											Iodine,
											Astatine,
											Tennessine));
		}
		
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
