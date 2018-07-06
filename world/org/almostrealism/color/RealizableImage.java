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

import org.almostrealism.util.Pipeline;
import org.almostrealism.util.Producer;

public class RealizableImage implements Producer<ColorProducer[][]> {
	private ColorProducer data[][];
	private Future<ColorProducer> futures[][];
	
	public RealizableImage(ColorProducer data[][]) { this.data = data; }
	
	public RealizableImage(Future<ColorProducer> data[][]) { this.futures = data; }

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
	public ColorProducer[][] evaluate(Object[] args) {
		if (this.futures == null) return data;

		Pipeline pipe = null;
		if (args.length > 0 && args[0] instanceof Pipeline) pipe = (Pipeline) args[0];

		ColorProducer p[][] = new ColorProducer[futures.length][futures[0].length];
		for (int i = 0; i < p.length; i++) {
			for (int j = 0; j < p[i].length; j++) {
				try {
					p[i][j] = (futures[i][j] == null || !futures[i][j].isDone()) ? null : futures[i][j].get();
				} catch (InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
			}

			if (pipe != null) pipe.evaluate(new Object[] { p });
		}
		
		return p;
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
