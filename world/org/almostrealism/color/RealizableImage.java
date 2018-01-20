/*
 * Copyright 2017 Michael Murray
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

import org.almostrealism.util.Producer;

public class RealizableImage implements Producer<ColorProducer[][]> {
	private ColorProducer data[][];
	private Future<ColorProducer> futures[][];
	
	public RealizableImage(ColorProducer data[][]) { this.data = data; }
	
	public RealizableImage(Future<ColorProducer> data[][]) { this.futures = data; }
	
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
