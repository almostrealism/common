package org.almostrealism.io;

import java.io.PrintStream;

public class PrintStreamPrintWriter implements PrintWriter {
	private PrintStream out;
	private int indent = 0;

	public PrintStreamPrintWriter(PrintStream out) {
		this.out = out;
	}

	@Override
	public void moreIndent() { indent++; }

	@Override
	public void lessIndent() { indent--; }

	@Override
	public void print(String s) {
		this.out.print(s);
	}

	@Override
	public void println(String s) {
		for (int i = 0; i < indent; i++) {
			print("\t");
		}

		print(s);
		println();
	}

	@Override
	public void println() {
		this.out.println();
	}
}
