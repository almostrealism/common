package org.almostrealism.chem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Technetium implements Element {
	public int getAtomicNumber() { return 43; }
	
	public Atom getAtom() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		ArrayList<Shell> s = new ArrayList<Shell>();
		s.addAll(PeriodicTable.Krypton.getShells());
		s.add(Shell.fourth(0, 0, 5, 0));
		s.add(Shell.fifth(2, 0, 0, 0));
		return Collections.unmodifiableList(s);
	}
}
