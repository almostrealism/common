/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.persistence;

import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.protobuf.Collections;

import java.util.stream.IntStream;

public class CollectionEncoder {
	public static Collections.CollectionData encode(PackedCollection<?> c) {
		return encode(c, Precision.FP64);
	}

	public static Collections.CollectionData encode(PackedCollection<?> c, Precision precision) {
		if (c == null) return Collections.CollectionData.getDefaultInstance();

		Collections.CollectionData.Builder data = Collections.CollectionData.newBuilder();
		data.setTraversalPolicy(encode(c.getShape()));

		if (precision == Precision.FP64) {
			c.doubleStream().forEach(data::addData);
		} else if (precision == Precision.FP32) {
			c.doubleStream().forEach(d -> data.addData32((float) d));
		} else {
			throw new IllegalArgumentException();
		}

		return data.build();
	}

	public static Collections.TraversalPolicyData encode(TraversalPolicy shape) {
		Collections.TraversalPolicyData.Builder data = Collections.TraversalPolicyData.newBuilder();
		IntStream.range(0, shape.getDimensions()).forEach(i -> data.addDims(shape.length(i)));
		data.setTraversalAxis(shape.getTraversalAxis());
		return data.build();
	}

	public static PackedCollection<?> decode(Collections.CollectionData data) {
		TraversalPolicy shape = decode(data.getTraversalPolicy());
		if (shape.getDimensions() == 0) return null;

		return decode(data, new PackedCollection<>(shape.getTotalSize()));
	}

	public static PackedCollection<?> decode(Collections.CollectionData data,
											 PackedCollection<?> destination) {
		return decode(data, destination, 0);
	}

	public static PackedCollection<?> decode(Collections.CollectionData data,
											 PackedCollection<?> destination,
											 int destinationOffset) {
		TraversalPolicy shape = decode(data.getTraversalPolicy());
		if (shape.getDimensions() == 0) return null;

		if (data.getDataList().isEmpty()) {
			float f[] = new float[data.getData32Count()];
			for (int i = 0; i < f.length; i++) {
				f[i] = data.getData32(i);
			}

			destination.setMem(destinationOffset, f);
		} else {
			destination.setMem(destinationOffset,
					data.getDataList().stream().mapToDouble(d -> d).toArray());
		}

		return destination.range(shape, destinationOffset);
	}

	public static TraversalPolicy decode(Collections.TraversalPolicyData data) {
		return new TraversalPolicy(true,
				data.getDimsList().stream().mapToInt(i -> i).toArray())
				.traverse(data.getTraversalAxis());
	}
}

