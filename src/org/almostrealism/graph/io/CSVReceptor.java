package org.almostrealism.graph.io;

import org.almostrealism.graph.ProteinCache;
import org.almostrealism.graph.ReceptorLambda;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

public class CSVReceptor<T> extends ReceptorLambda<T> {
	private PrintWriter ps;
	private long index;

	public CSVReceptor(OutputStream out) {
		this(null, out);
	}

	public CSVReceptor(ProteinCache<T> cache, OutputStream out) {
		super(cache, null);
		ps = new PrintWriter(new OutputStreamWriter(out));
		setLambda((c, p) -> { ps.println(index++ + "," + c.getProtein(p)); ps.flush(); });
	}
}
