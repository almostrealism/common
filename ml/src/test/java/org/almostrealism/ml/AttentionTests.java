/*
 * Copyright 2023 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml;

import io.almostrealism.code.OperationProfile;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class AttentionTests implements AttentionFeatures, TestFeatures {
	@Test
	public void attentionKeys() {
		int seqLength = 4; // 1024;
		int heads = 12; // 12;
		int headSize = 64; // 64;
		int dim = heads * headSize;

		TraversalPolicy inputShape = shape(heads, headSize);
		TraversalPolicy keyShape = shape(seqLength, heads, headSize);
		TraversalPolicy outputShape = shape(heads, seqLength);

		PackedCollection<?> q = new PackedCollection<>(inputShape); // (heads, headSize)
		PackedCollection<?> keyCache = new PackedCollection<>(keyShape); // (seqLength, heads, headSize)
		PackedCollection<?> att = new PackedCollection<>(outputShape); // (heads, seqLength)

		q.fill(pos -> Math.random());
		keyCache.fill(pos -> Math.random());

		Producer<PackedCollection<?>> o = c(p(keyCache)).traverse(1).map(v -> v.multiply(p(q)))
											.traverse(2).sum()
											.divide(c(Math.sqrt(headSize)))
											.reshape(shape(seqLength, heads));
		att = o.get().evaluate();
		att = c(p(att)).enumerate(1, 1).reshape(outputShape).get().evaluate();

//		int p = (int) (0.8 * seqLength);
		int p = seqLength - 1;

		for (int h = 0; h < heads; h++) {
			for (int t = 0; t <= p; t++) {
				double score = 0.0;

				for (int i = 0; i < headSize; i++) {
					score += q.valueAt(h, i) * keyCache.valueAt(t, h, i);
				}

				score /= Math.sqrt(headSize);

				System.out.println("AttentionTests[" + t + "]: " + score + " vs " + att.valueAt(h, t));
				assertEquals(score, att.valueAt(h, t));
			}
		}

//		for (int h = 0; h < heads; h++) {
//			for (int t = 0; t <= p; t++) {
//				double score = 0.0;
//				for (int i = 0; i < headSize; i++) {
//					score += q.valueAt(h, i) * keyCache.valueAt(t, h, i);
//				}
//				score /= Math.sqrt(headSize);
//
//				System.out.println("AttentionTests[" + t + "]: " + score + " vs " + att.valueAt(h, t));
//				assertEquals(score, att.valueAt(h, t));
//			}
//		}
	}


	@Test
	public void attentionValues() {
		int seqLength = 1024;
		int heads = 12;
		int headSize = 64;
		int dim = heads * headSize;

		TraversalPolicy inputShape = shape(heads, seqLength);
		TraversalPolicy valueShape = shape(seqLength, heads, headSize);
		TraversalPolicy outputShape = shape(heads, headSize);
		TraversalPolicy finalShape = outputShape.flatten();

		PackedCollection<?> att = new PackedCollection<>(inputShape); // (heads, seqLength)
		PackedCollection<?> values = new PackedCollection<>(valueShape); // (seqLength, heads, headSize)
		PackedCollection<?> out = new PackedCollection<>(finalShape); // (heads, headSize)

		att.fill(pos -> Math.random());
		values.fill(pos -> Math.random());

//		int p = (int) (0.8 * seqLength);
		int p = seqLength - 1;

		CollectionProducer<PackedCollection<?>> v = c(p(values)).reshape(shape(seqLength, dim))
														.enumerate(1, 1)
														.reshape(shape(heads, headSize, seqLength));
		CollectionProducer<PackedCollection<?>> a = c(p(att)).traverse(1).expand(headSize, x -> x.repeat(headSize));
		CollectionProducer<PackedCollection<?>> o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum().reshape(finalShape);

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("attention", false);
		op.add(a("attentionValues", traverseEach(p(out)), o));
		((OperationList) op.optimize()).get(profiles).run();

		profiles.print();

//		out = o.get().evaluate().reshape(finalShape);

		for (int h = 0; h < heads; h++) {
			for (int i = 0; i < headSize; i++) {
				double vo = 0.0;

				for (int t = 0; t <= p; t++) {
					vo += att.valueAt(h, t) * values.valueAt(t, h, i);
				}

				System.out.println("AttentionTests[" + i + "]: " + vo + " vs " + out.valueAt(h * headSize + i));
				assertEquals(vo, out.valueAt(h * headSize + i));
			}
		}
	}
}
