package org.almostrealism.chem;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Hydrogen implements Element {
	public int getAtomicNumber() { return 1; }

	public Atom getAtom() { return new Atom(getShells()); }
	
	private List<Shell> getShells() {
		return Collections.unmodifiableList(Arrays.asList(Shell.first(1)));
	}
}
