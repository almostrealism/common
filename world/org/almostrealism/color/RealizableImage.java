/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.color;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.almostrealism.algebra.Pair;
import org.almostrealism.util.Pipeline;
import org.almostrealism.util.Producer;

public class RealizableImage implements Producer<Producer<RGB>[][]> {
	private Producer<RGB> data[][];
	private Future<Producer<RGB>> futures[][];

	private Producer<RGB> source;
	private Pair dim;
	
	public RealizableImage(Producer<RGB> data[][]) { this.data = data; }
	
	public RealizableImage(Future<Producer<RGB>> data[][]) { this.futures = data; }

	public RealizableImage(Producer<RGB> source, Pair dimensions) {
		this.source = source;
		this.dim = dimensions;
	}

	public Producer<RGB> getSource() { return source; }

	public boolean isCompletelySubmitted() {
		if (futures == null) return true;

		for (int i = 0; i < futures.length; i++) {
			for (int j = 0; j < futures[i].length; j++) {
				if (futures[i][j] == null) return false;
			}
		}

		return true;
	}

	public boolean isComplete() {
		if (futures == null) return true;

		for (int i = 0; i < futures.length; i++) {
			for (int j = 0; j < futures[i].length; j++) {
				if (futures[i][j] == null || !futures[i][j].isDone()) return false;
			}
		}

		return true;
	}

	public double getCompleted() {
		if (futures == null) return 1.0;

		int completed = 0, total = 0;

		for (int i = 0; i < futures.length; i++) {
			for (int j = 0; j < futures[i].length; j++) {
				if (futures[i][j] != null && futures[i][j].isDone()) completed++;
				total++;
			}
		}

		return ((double) completed) / total;
	}

	@Override
	public Producer<RGB>[][] evaluate(Object[] args) {
		Pipeline pipe = null;
		if (args.length > 0 && args[0] instanceof Pipeline) pipe = (Pipeline) args[0];

		if (source == null) {
			if (this.futures == null) return data;

			Producer<RGB> p[][] = new Producer[futures.length][futures[0].length];
			for (int i = 0; i < p.length; i++) {
				for (int j = 0; j < p[i].length; j++) {
					try {
						p[i][j] = (futures[i][j] == null || !futures[i][j].isDone()) ? null : futures[i][j].get();
					} catch (InterruptedException | ExecutionException e) {
						e.printStackTrace();
					}
				}

				if (pipe != null) pipe.evaluate(new Object[]{ p });
			}

			return p;
		} else {
			// TODO  Theres a better way of doing this
			Producer<RGB> p[][] = new Producer[(int) dim.getX()][(int) dim.getY()];

			for (int i = 0; i < p.length; i++) {
				for (int j = 0; j < p[i].length; j++) {
					p[i][j] = source;
				}

				if (pipe != null) pipe.evaluate(new Object[]{ p });
			}

			return p;
		}
	}

	/**
	 * If this {@link RealizableImage} is backed by actual {@link ColorProducer}s,
	 * {@link Producer#compact()} is called on each of them. If {@link Future}s
	 * are being used instead, this method does nothing.
	 */
	@Override
	public void compact() {
		if (data == null) return;

		for (int i = 0; i < data.length; i++) {
			for (int j = 0; j < data[i].length; j++) {
				if (data[i][j] != null) data[i][j].compact();
			}
		}
	}
}
