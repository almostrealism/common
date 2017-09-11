package org.almostrealism.color;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.almostrealism.util.Producer;

public class RealizableImage implements Producer<ColorProducer[][]> {
	private ColorProducer data[][];
	private Future<ColorProducer> futures[][];
	
	public RealizableImage(ColorProducer data[][]) {
		this.data = data;
	}
	
	public RealizableImage(Future<ColorProducer> data[][]) {
		this.futures = data;
	}
	
	@Override
	public ColorProducer[][] evaluate(Object[] args) {
		if (this.futures == null) return data;
		
		ColorProducer p[][] = new ColorProducer[futures.length][futures[0].length];
		for (int i = 0; i < p.length; i++) {
			for (int j = 0; j < p[i].length; j++) {
				try {
					p[i][j] = futures[i][j].get();
				} catch (InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
			}
		}
		
		return p;
	}
}
