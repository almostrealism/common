package org.almostrealism.graph;

public class ReceptorLambda<T> implements Receptor<T> {
	private ProteinCache<T> cache;
	private Lambda<T> lambda;

	public ReceptorLambda(Lambda<T> l) {
		this.lambda = l;
	}

	public ReceptorLambda(ProteinCache<T> cache, Lambda<T> l) {
		this.cache = cache;
		this.lambda = l;
	}

	@Override
	public void push(long proteinIndex) {
		this.lambda.push(cache, proteinIndex);
	}

	@Override
	public void setProteinCache(ProteinCache<T> p) {
		this.cache = p;
	}

	protected void setLambda(Lambda<T> l) { this.lambda = l; }

	public interface Lambda<T> {
		void push(ProteinCache<T> cache, long proteinIndex);
	}
}
