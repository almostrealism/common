package org.almostrealism.econ;

import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

public abstract class ExpenseData extends HashMap<Time, Expense> {
	public void write(OutputStream out) {
		try (XMLEncoder e = new XMLEncoder(out)) {
			e.writeObject(this);
		}
	}

	public static ExpenseData read(InputStream in) {
		try (XMLDecoder d = new XMLDecoder(in)) {
			return (ExpenseData) d.readObject();
		}
	}
}
