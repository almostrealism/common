/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.ComplexNumber;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSettings;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class MyNativeEnabledApplication implements CodeFeatures {
	public static void main(String args[]) {
		new MyNativeEnabledApplication().performMath();
	}

	@Test
	public void performMath() {
		// Compose the expression
		Producer<PackedCollection> constantOperation = c(3.0).multiply(c(2.0));

		// Compile the expression
		Evaluable<PackedCollection> compiledOperation = constantOperation.get();

		// Evaluate the expression
		StringBuffer displayResult = new StringBuffer();
		displayResult.append("3 * 2 = ");
		compiledOperation.evaluate().print(displayResult::append);

		// Display the result
		System.out.println(displayResult);
	}

	@Test
	public void createTensor() {
		// Create the tensor
		Tensor<Double> t = new Tensor<>();
		t.insert(1.0, 0, 0);
		t.insert(2.0, 0, 1);
		t.insert(5.0, 0, 2);

		Tensor<Double> p = new Tensor<>();
		p.insert(3.0, 0);
		p.insert(4.0, 1);
		p.insert(5.0, 2);

		// Prepare the computation
		CollectionProducer<PackedCollection> product = multiply(c(t.pack()), c(p.pack()));

		// Compile the computation and evaluate it
		product.get().evaluate().print();

		// Note that you can also combine compilation and evaluation into
		// one step, if you are not planning to reuse the compiled expression
		// for multiple evaluations.
		product.evaluate().print();
	}

	@Test
	public void variableMath() {
		// Define argument 0
		Producer<PackedCollection> arg = v(shape(2), 0);

		// Compose the expression
		Producer<PackedCollection> constantOperation = c(7.0).multiply(arg);

		// Compile the expression
		Evaluable<PackedCollection> compiledOperation = constantOperation.get();

		// Evaluate the expression repeatedly
		System.out.println("7 * 3 | 7 * 2 = ");
		compiledOperation.evaluate(pack(3, 2)).print();
		System.out.println("7 * 4 | 7 * 3 = ");
		compiledOperation.evaluate(pack(4, 3)).print();
		System.out.println("7 * 5 | 7 * 4 = ");
		compiledOperation.evaluate(pack(5, 4)).print();
	}

	@Test
	public void performThreeExperiments() {
		for (int i = 0; i < 3; i++) {
			dc(() -> {
				// Define argument 0
				Producer<PackedCollection> arg = v(shape(2), 0);

				// Compose the expression
				Producer<PackedCollection> constantOperation = c(7.0).multiply(arg);

				// Compile the expression
				Evaluable<PackedCollection> compiledOperation = constantOperation.get();

				// Evaluate the expression repeatedly
				System.out.println("7 * 3 | 7 * 2 = ");
				compiledOperation.evaluate(pack(3, 2)).print();
				System.out.println("7 * 4 | 7 * 3 = ");
				compiledOperation.evaluate(pack(4, 3)).print();
				System.out.println("7 * 5 | 7 * 4 = ");
				compiledOperation.evaluate(pack(5, 4)).print();
			});
		}
	}

	@Test
	public void useCpuAndGpu() {
		PackedCollection result = new PackedCollection(shape(1));

		Producer<PackedCollection> sum = add(c(1.0), c(2.0));
		Producer<PackedCollection> product = multiply(c(3.0), c(2.0));

		cc(() -> a(1, p(result), sum).get().run(), ComputeRequirement.CPU);
		log("Result = " + result.toArrayString());

		cc(() -> a(1, p(result), product).get().run(), ComputeRequirement.GPU);
		log("Result = " + result.toArrayString());
	}

	@Test
	public void kernelEvaluation() {
		// Define argument 0
		Producer<PackedCollection> arg = v(shape(-1), 0);

		// Compose the expression
		Producer<PackedCollection> constantOperation = c(7.0).multiply(arg);

		// Compile the expression
		Evaluable<PackedCollection> compiledOperation = constantOperation.get();

		PackedCollection bank = new PackedCollection(shape(3)).traverse();
		bank.set(0, 3.0);
		bank.set(1, 4.0);
		bank.set(2, 5.0);

		PackedCollection results = new PackedCollection(shape(3)).traverse();

		// Evaluate the expression with the accelerator deciding how to parallelize it
		compiledOperation.into(results).evaluate(bank);

		System.out.println("7 * 3, 7 * 4, 7 * 5 = ");
		results.print();
	}

	@Test
	public void shapes() {
		// The shape is a 3D array with 10x4x2 elements, and 80 elements in total.
		// However, it will be treated for the purpose of GPU parallelism as one
		// value with 80 elements. In this case, 1 is referred to as the count and
		// 80 is referred to as the size.
		TraversalPolicy shape = shape(10, 4, 2);
		System.out.println("Shape = " + shape.toStringDetail());
		// Shape = (10, 4, 2)[axis=0|1x80]
		//           <dims> [Count x Size]

		// What if we want to operate on groups of elements at once, via SIMD or
		// some other method? We can simply adjust the traversal axis
		shape = shape.traverse();
		System.out.println("Shape = " + shape.toStringDetail());
		// Shape = (10, 4, 2)[axis=1|10x8]
		//           <dims> [Count x Size]
		// --> Now we have 10 groups of 8 elements each, and 10 operations can work on
		// 8 elements each - at the same time.

		shape = shape.traverseEach(); // Move the traversal axis to the innermost dimension
		shape = shape.consolidate(); // Move the traversal axis back by 1 position
		shape = shape.item(); // Pull off just the shape of one item in the parallel group
		System.out.println("Shape = " + shape.toStringDetail());
		// Shape = (2)[axis=0|1x2]
		// --> And that's just one item from the original shape (which contained 40 of them).
	}

	@Test
	public void repeat() {
		PackedCollection a = pack(2, 3).reshape(2, 1);
		PackedCollection b = pack(4, 5).reshape(2);
		c(a).traverse(1).repeat(2).multiply(c(b)).evaluate().print();
	}

	@Test
	public void enumerate() {
		PackedCollection a =
					pack(2, 3, 4, 5, 6, 7, 8, 9)
						.reshape(2, 4);
		PackedCollection r = c(a).enumerate(1, 2, 2).evaluate();
		System.out.println(r.getShape().toStringDetail());
		// Shape = (2, 2, 2)[axis=0|1x8]

		r.traverse(2).print();
		// [2.0, 3.0]
		// [6.0, 7.0]
		// [4.0, 5.0]
		// [8.0, 9.0]
	}

	@Test
	public void subset3d() {
		int w = 2;
		int h = 4;
		int d = 3;

		int x0 = 4;
		int y0 = 3;
		int z0 = 2;

		PackedCollection a = new PackedCollection(shape(10, 10, 10));
		a.fill(Math::random);

		CollectionProducer<PackedCollection> producer = subset(shape(w, h, d), c(a), x0, y0, z0);
		Evaluable<PackedCollection> ev = producer.get();
		PackedCollection subset = ev.evaluate();

		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				for (int k = 0; k < d; k++) {
					double expected = a.valueAt(x0 + i, y0 + j, z0 + k);
					double actual = subset.valueAt(i, j, k);
					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}

	@Test
	public void polynomialDelta() {
		// x^2 + 3x + 1
		CollectionProducer<PackedCollection> c = x().sq().add(x().mul(3)).add(1);

		// y = f(x)
		Evaluable<PackedCollection> y = c.get();
		PackedCollection out = y.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.consolidate().print();

		// dy = f'(x) = 2x + 3
		Evaluable<PackedCollection> dy = c.delta(x()).get();
		out = dy.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.consolidate().print();
	}

	@Test
	public void vectorDelta() {
		int dim = 3;
		int count = 2;

		PackedCollection v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection w = pack(4, -3, 2);
		CollectionProducer<PackedCollection> x = x(dim);

		// w * x
		CollectionProducer<PackedCollection> c = x.mul(p(w));

		// y = f(x)
		Evaluable<PackedCollection> y = c.get();
		PackedCollection out = y.evaluate(v);
		out.print();

		// dy = f'(x)
		//    = w
		Evaluable<PackedCollection> dy = c.delta(x).get();
		PackedCollection dout = dy.evaluate(v);
		dout.print();
	}

	@Test
	public void complexMath() {
		ComplexNumber a = new ComplexNumber(1, 2);
		ComplexNumber b = new ComplexNumber(3, 4);

		Producer<ComplexNumber> c = (Producer) multiplyComplex(c(a), c(b));
		System.out.println("(1 + 2i) * (3 + 4i) = ");
		c.evaluate().print();
	}

	@Test
	public void trainCnn() {
		if (!TestSettings.trainingTests) return;

		int s = 10;

		Tensor<Double> t = new Tensor<>();
		shape(s, s).stream().forEach(pos -> t.insert(0.5 + 0.5 * Math.random(), pos));

		PackedCollection input = t.pack();
		train(input, model(s, s, 3, 8, 10));
	}

	protected void train(PackedCollection input, Model model) {
		CompiledModel compiled = model.compile();
		log("Model compiled");

		int epochSize = 1000;
		int count = 100 * epochSize;

		for (int i = 0; i < count; i++) {
			input.fill(pos -> 0.5 + 0.5 * Math.random());

			compiled.forward(input);

			if (i % 1000 == 0) {
				log("Input Size = " + input.getShape() +
						"\t | epoch = " + i / epochSize);
			}

			compiled.backward(rand(model.lastBlock().getOutputShape()).get().evaluate());

			if (i % 1000 == 0) {
				log("\t\tbackprop\t\t\t | epoch = " + i / epochSize);
			}
		}
	}

	protected Model model(int r, int c, int convSize, int convFilters, int denseSize) {
		Model model = new Model(shape(r, c));
		model.add(convolution2d(convFilters, convSize));
		model.add(pool2d(2));
		model.add(flattened());
		model.add(dense(denseSize));
		model.add(softmax());
		log("Created model (" + model.getBlocks().size() + " blocks)");
		return model;
	}
}
