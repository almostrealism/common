<a name="readme-top"></a>

[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![Apache License][license-shield]][license-url]

<h3 align="center">Almost Realism Scientific Computing and Machine Learning Libraries</h3>
<h5 align="center">[![Qodana](https://github.com/almostrealism/common/actions/workflows/analysis.yaml/badge.svg)](https://github.com/almostrealism/common/actions/workflows/analysis.yaml)</h5>
<h5 align="center">Tools for high-performance scientific computing, generative art, and machine learning in Java
with pluggable native acceleration.</h5>

  <p align="center">
    Currently supporting OpenCL (X86/ARM) and Metal (Aarch64), CUDA support in progress.
    <br />
    <a href="https://github.com/almostrealism/common/issues">Report Bug</a>
    ·
    <a href="https://github.com/almostrealism/common/issues">Request Feature</a>
  </p>

  <p align="right">
    <i>If you are interested in a part-time, paid position, developing common ML model architectures as part of
    our ongoing project to increase the stability and performance of this framework - please email
    <a href="https://twitter.com/ashesfall">michael</a> @ almostrealism.com</i>
    <br>
    <img src="https://img.shields.io/github/contributors/almostrealism/common.svg?style=for-the-badge"/>
  </p>

### What does this do?
It provides data structures for operations in algebra, geometry, and other mathematics along
with datatypes for both video and audio that are useful in both scientific computations and
the automated production of artwork. These libraries provide abstractions that can be used
at runtime with a whole range of different acceleration strategies, so you do not have to make
a commitment to a particular strategy for production use of your model code ahead of time.

There is a complete implementation of n-dimensional arrays, but unlike other acceleration
frameworks where specific operations are accelerated, this library provides a mechanism for
compiling an entire accelerator program from a hierarchy of mathematical operations. This
makes it potentially faster than systems which are designed to perform certain common operations
quickly, but are not capable of generating custom accelerator code.

Machine learning capabilities will be expanded substantially over the remainder of 2023, but
an early example of a neural network is provided at the end of this document.

Using this library correctly allows you to take complex operations, written in Java, and end
up with binaries for CPU, GPU, or FPGA that are as fast or faster than hand-written native code.

 *Note: A subset of this documentation for use as the preamble to a LLM prompt is available in PROMPT.md*
 *(This can sometimes be an easier way to get help using the library, than reading this yourself.)*

#### Support Accelerators
    1. Standard JNI Operations via runtime generated .so/.dylib (x86/Aarch64)
    2. OpenCL on CPU (x86/Aarch64)
    3. OpenCL on GPU (x86/Aarch64)
    4. Metal (JNI with dylib) on GPU (Aarch64)
    5. External Native Operations via a generated executable (x86/Aarch64)

*For more information about the Java bindings for OpenCL used here, visit jocl.org*
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Why would you want this?
When choosing Java as your target language, you are normally making a trade-off related to
leveraging native instruction sets. The assumption is normally that applications can benefit
from the JVM in a way that makes it worth sacrificing access to native instruction sets like
AVX2 or native frameworks like CL and Metal. The Almost Realism Libraries eliminate this trade
off, allowing you to create Java applications that make use of acceleration without breaking
the design patterns that are normally used in Java development.

This also means you can, for example, define ML workflows and other HPC processes in Scala,
Kotlin or Groovy. If you've ever been frustrated trying to take a Python project to production,
this library should be able to make it unnecessary. You also can run your programs on any
machine with an OpenCL compatible device and a JVM, without compiling on the target system.
This means no more of the headache of "numpy failed to build on this Amazon Graviton machine",
etc.

### What does it depend on?
The dependency footprint is unbelievably small. The only dependency that is brought with
this library results from your choice of accelerator. To use JOCL you will need the native
bindings for CL. They are available from jocl.org.
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### To use the libraries

Add Maven Repository:

        <repositories>
                <repository>
                        <id>almostrealism</id>
                        <name>Almost Realism/name>
                        <url>https://maven.pkg.github.com/almostrealism/common</url>
                        <releases><enabled>true</enabled></releases>
                        <snapshots><enabled>true</enabled></snapshots>
                </repository>
        </repositories>

Add utils:

        <dependency>
            <groupId>org.almostrealism</groupId>
            <artifactId>ar-utils</artifactId>
            <version>0.62</version>
        </dependency>

### Enabling Your Application

All of the library functionality is provided as default methods of an interface called
**CodeFeatures**

```Java
    public class MyNativeEnabledApplication implements CodeFeatures {
	    public static void main(String args[]) {
			new MyNativeEnabledApplication().performMath();
		}
		
		public void performMath() {
			// .. include native operations here
		}
    }
```

Simple mathematical operations on constant values are compact to express, using
the c() method to create a constant value, and the multiply() method to create a
multiplication operation. The get() method is used to compile the operation, and
the evaluate() method is used to execute it. The result is a PackedCollection, a
generic datastructure for storing numbers in a fixed arrangement in memory.
(When you are not using a fixed arrangement, you can use the Tensor class discussed
below instead).

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
	// ....

	public void performMath() {
		// Compose the expression
		Producer<PackedCollection<?>> constantOperation = c(3.0).multiply(c(2.0));

		// Compile the expression
		Evaluable<PackedCollection<?>> compiledOperation = constantOperation.get();

		// Evaluate the expression
		StringBuffer displayResult = new StringBuffer();
		displayResult.append("3 * 2 = ");
		compiledOperation.evaluate().print(displayResult::append);

		// Display the result
		System.out.println(displayResult);
	}
}
```

When the expression is compiled it will be converted to the target accelerator. This might
be an OpenCL kernel program, an entirely separate native process or library, or something else.
You can write your entire application this way without having to decide which backend to use,
or you can use different backends in different places - leveraging Metal on MacOS, while
using a native lib on windows and an external native process in the cloud - all with the same
language for defining your expressions.
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Tutorial

*All of these examples are found in the test directory of the utils module in this repository*

#### Using Tensor

Tensor is a data structure that is used to represent multi-dimensional arrays of data. Before
a tensor can be used in computations, it has to be packed (at which point it's shape becomes
immutable).

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
	// ....

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
		CollectionProducer<PackedCollection<?>> product = multiply(c(t.pack()), c(p.pack()));

		// Compile the computation and evaluate it
		product.get().evaluate().print();
		
		// Note that you can also combine compilation and evaluation into
		// one step, if you are not planning to reuse the compiled expression
		// for multiple evaluations.
		product.evaluate().print();
	}
}
```

#### Using Variables
Mathematical operations can use both constant values and variable values:

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
	// ....

	public void variableMath() {
		// Define argument 0
		Producer<PackedCollection<?>> arg = v(shape(2), 0);

		// Compose the expression
		Producer<PackedCollection<?>> constantOperation = c(7.0).multiply(arg);

		// Compile the expression
		Evaluable<PackedCollection<?>> compiledOperation = constantOperation.get();

		// Evaluate the expression repeatedly
		System.out.println("7 * 3 | 7 * 2 = ");
		compiledOperation.evaluate(pack(3, 2)).print();
		System.out.println("7 * 4 | 7 * 3 = ");
		compiledOperation.evaluate(pack(4, 3)).print();
		System.out.println("7 * 5 | 7 * 4 = ");
		compiledOperation.evaluate(pack(5, 4)).print();
	}
}
```
This also shows some other features, including the pack() method for reserving memory,
and that operations can broadcast over different shapes.

#### Controlling Execution

It is not normally required to worry about how resources on the underlying system are reserved
and managed, but in case it is important for your use case there are two **Context** concepts
that allow for more power over when and how non-JVM resources are allocated. **DataContext**s
are the top level **Context** concept. No memory can be shared between **DataContext**s, so
most applications will use a single **DataContext**. However, there may be scenarios where it
is desirable to wipe all the off-heap data that is used (given that there is no garbage
collection this actually becomes quite important for longer-running "service" applications).
<p align="right">(<a href="#readme-top">back to top</a>)</p>

**DataContext**s are global to the JVM; there is only ever one **DataContext** in effect at a
time.

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
	// ....

	public void performThreeExperiments() {
		for (int i = 0; i < 3; i++) {
			dc(() -> {
				// Define argument 0
				Producer<PackedCollection<?>> arg = v(shape(2), 0);

				// Compose the expression
				Producer<PackedCollection<?>> constantOperation = c(7.0).multiply(arg);

				// Compile the expression
				Evaluable<PackedCollection<?>> compiledOperation = constantOperation.get();

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
}
```

In this example, our accelerated operations are repeated 3 times. However, after each one, all
of the resources are destroyed. This corresponds to terminating the cl_context if you are using
OpenCL, or wiping local storage if you are using an external executable, etc.


The other **Context** concept, **ComputeContext**, exists within a particular **DataContext**.
A **ComputeContext** tracks only the compiled **InstructionSet**s, and memory can be shared
across different **ComputeContext**s. Obviously not every kind of **ComputeContext** can be
used with every kind of memory: if you are using **CLDataContext** and memory is stored
on a GPU device, there is no way to use it with, for example, a **ExternalComputeContext** without
incurring a lot of costly memory copying, so be careful with these choies.

**ComputeContexts** are global to the **Thread**, a JVM can have multiple **ComputeContexts**
at once, if multiple **Thread**s were used to create them.

When creating a **ComputeContext**, you can instruct the **DataContext** of your expectations,
and it will make a best effort to fulfill them.

*Note: It will not fail when your expectations cannot be met, it will
just provide computing resources other than what you expected.*

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
	// ....

	public void useCpuAndGpu() {
		PackedCollection<?> result = new PackedCollection(shape(1));

		Producer<PackedCollection<?>> sum = add(c(1.0), c(2.0));
		Producer<PackedCollection<?>> product = multiply(c(3.0), c(2.0));

		cc(() -> a(2, p(result), sum).get().run(), ComputeRequirement.CPU);
		System.out.println("Result = " + result.toArrayString());

		cc(() -> a(2, p(result), product).get().run(), ComputeRequirement.GPU);
		System.out.println("Result = " + result.toArrayString());
	}
}
```

In this example, we will perform addition using some available context that supports the CPU,
but we will perform multiplication using a (potentially separate) context that supports the GPU.
The example also shows some other features, including the a() method for assignment. Assignment
produces a **Runnable** rather than an **Evaluable**, and the run() method is used to execute it.
Be aware that, the **ComputeContext** which is used for a given **Evaluable** or **Runnable** is
always the one that was in effect when the **Evaluable** or **Runnable** was compiled via the 
get() method.

*Note: The contexts available on a given machine will depend on the hardware.*
<p align="right">(<a href="#readme-top">back to top</a>)</p>

#### Parallelization via Accelerator
Although you can use the tool with multiple threads (compiled operations are threadsafe),
you may want to leverage parallelization that cannot be accomplished with Java's Thread
concept. If you are targeting a GPU with OpenCL or Metal, for example, you'll want to
express a collection of operations with a single operation. This works the same way.

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
	// ....

	public void kernelEvaluation() {
		// Define argument 0
		Producer<PackedCollection<?>> arg = v(shape(1), 0);

		// Compose the expression
		Producer<PackedCollection<?>> constantOperation = c(7.0).multiply(arg);

		// Compile the expression
		Evaluable<PackedCollection<?>> compiledOperation = constantOperation.get();

		PackedCollection<?> bank = new PackedCollection<>(shape(3)).traverse();
		bank.set(0, 3.0);
		bank.set(1, 4.0);
		bank.set(2, 5.0);

		PackedCollection<?> results = new PackedCollection<>(shape(3)).traverse();

		// Evaluate the expression with the accelerator deciding how to parallelize it
		compiledOperation.into(results).evaluate(bank);

		System.out.println("7 * 3, 7 * 4, 7 * 5 = ");
		results.print();
	}
}
```

There are a few nuances here, besides the improved performance of parallelization via SIMD
or other kernel features of your available hardware. One is the Evaluable::into method, which
allows you to specify the destination of the results. This is useful when you are using (or
reusing) a pre-existing data structure. The other is the traverse() method, which is used to
adjust the traversal axis of the PackedCollection. More on this in the next section.
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### TraversalPolicy

Every data structure that deals with one or more collections of numbers has a **TraversalPolicy**
that tells other components how it is expected to be traversed during a computation. Some useful
properties of the **TraversalPolicy** (often called the shape) are shown in the example below.

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
	// ....

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
}
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### More Collection Operations

There are plenty of other operations besides the ones described in the tutorial. They are
covered briefly here.

#### Repeat

The repeat operation is used to repeat the item (see TraversalPolicy::item, described above)
of a collection some finite number of times. This is useful for broadcasting operations in
different ways.

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
    // ....

	public void repeat() {
		PackedCollection<?> a = pack(2, 3).reshape(2, 1);
		PackedCollection<?> b = pack(4, 5).reshape(2);
		c(a).traverse(1).repeat(2).multiply(c(b)).evaluate().print();
	}
}
```

This example will produces [8, 10, 12, 15]. Notice how the broadcast behavior here is different
because of the repeat operation - because the 2x1 collection has each item (size 1) repeated twice,
producing [2, 2, 3, 3] which is then multiplied by the other value to produce a result. Notice
how the broadcast behavior here is different because of the repeat operation.

#### Enumerate

The enumerate operation is used to move over a collection some finite number of times, along some
axis of that collection. This is useful for performing a lot of common tensor operations, like
transpose or convolution. The enumerate() method accepts an axis, a length, and a stride.

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
    // ....

	public void enumerate() {
		PackedCollection<?> a =
				pack(2, 3, 4, 5, 6, 7, 8, 9)
						.reshape(2, 4);
		PackedCollection<?> r = c(a).enumerate(1, 2, 2).evaluate();
		System.out.println(r.getShape().toStringDetail());
		// Shape = (2, 2, 2)[axis=3|8x1]

		r.consolidate().print();
		// [2.0, 3.0]
		// [6.0, 7.0]
		// [4.0, 5.0]
		// [8.0, 9.0]
	}
}
```

Here the enumerate operation iterates over axis 1, collecting 2 numbers at a time, and then
advancing by 2 along the axis. The result is a pair of 2x2 collections, a 2x2x2 tensor. The
stride can also be omitted if it is the same as the length, as it is in the example.

#### Subset

To take just a slice of a collection, the subset operation can be used. It accepts shape and
position information for the slice.

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
    // ....

	public void subset3d() {
		int w = 2;
		int h = 4;
		int d = 3;

		int x0 = 4;
		int y0 = 3;
		int z0 = 2;

		PackedCollection<?> a = new PackedCollection<>(shape(10, 10, 10));
		a.fill(Math::random);

		CollectionProducer<PackedCollection<?>> producer = subset(shape(w, h, d), c(a), x0, y0, z0);
		Evaluable<PackedCollection<?>> ev = producer.get();
		PackedCollection<?> subset = ev.evaluate();

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
}
```

#### More Complex Operations

All these atomic operations (together with the standard mathematical operations) can be combined to achieve
basically any kind of tensor algebra you may need. If you find otherwise, please 
[open an issue][issues-url]
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Special Purpose Types

Although **PackedCollection** is a general purpose data structure for laying out numerical values in
memory, and any operation can ultimately use it, there are other types that extend **PackedCollection**
to provide functionality that may be specific to a particular domain.

#### Pair

The **Pair** type is used to store two values. This seems like it wouldn't require a type of its own,
but there are some unique things that make sense only for a pair of values. Some **Pair** subclasses
are listed below.

1. **ComplexNumber** - A pair of real numbers, used together to represent a complex number.
2. **TemporalScalar** - A pair of a number and a time, used to represent a scalar values distributed along a timeseries.
3. **Scalar** - A number and a corresponding uncertainty, used to represent a measurement that may not be precisely known.
4. **Photon** - A wavelength and a phase, used to represent a photon in a manner that accounts for quantum interference.
5. **CursorPair** - A pair of values that form an interval.

#### Vector

The **Vector** type stores three values and is used for geometric operations, especially 3D graphics.
It has one subclass, **Vertex**, which represents a position in space but includes references to values
for a normal or gradient and a **Pair** of texture coordinates.

#### RGB

The **RGB** type stores three values and is used for color operations. It has one subclass, **RGBA**, which
keeps a reference to an additional value for an alpha (transparency) channel.

#### MeshData

The **MeshData** type is used to store the data for a 3D mesh. It directly represents all the data needed
for rendering a triagulated 3D object, including the positions of the vertices and the normal/gradient values.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Other Mathematical Operations

#### Complex Numbers

Given that the sum of two complex numbers is the sum of their real and imaginary parts, there is no need
for special handling of complex addition and subtraction, the **CollectionProducer**::add and
**CollectionProducer**::subtract methods work just as well as for other types of data.  However, the
process of multiplying two complex numbers is not trivially reducible to multiplication of the
individual values, and hence cannot be accomplished with some variation of the tensor multiplication
provided by **CollectionProducer**::multiply (this is similarly true for exponentiation, etc).

For this case, special methods are available.

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
    // ....

	public void complexMath() {
		ComplexNumber a = new ComplexNumber(1, 2);
		ComplexNumber b = new ComplexNumber(3, 4);

		Producer<ComplexNumber> c = multiplyComplex(c(a), c(b));
		System.out.println("(1 + 2i) * (3 + 4i) = ");
		c.evaluate().print();
	}
}
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Automatic Differentiation

Most **Producer** implementations, mainly those that implement **ProducerComputation**, support automatic
differentiation. This is a powerful feature that allows you to compute the gradient of a function with
respect to any of its inputs. This is especially useful for machine learning implementations, but that is
far from the only use case.

#### Delta

The delta() method is used to form a new **Producer** that is the gradient of the original **Producer** with
respect to a particular input. The shape of the result will be the shape of the target input appended to the
output shape - resulting in a derivative for each combination of input and output. Some examples follow.

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
    // ....

	@Test
	public void polynomialDelta() {
		// x^2 + 3x + 1
		CollectionProducer<PackedCollection<?>> c = x().sq().add(x().mul(3)).add(1);

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.consolidate().print();

		// dy = f'(x) = 2x + 3
		Evaluable<PackedCollection<?>> dy = c.delta(x()).get();
		out = dy.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.consolidate().print();
	}

	@Test
	public void vectorDelta() {
		int dim = 3;
		int count = 2;

		PackedCollection<?> v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w = pack(4, -3, 2);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// w * x
		CollectionProducer<PackedCollection<?>> c = x.mul(p(w));

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(v);
		out.print();

		// dy = f'(x)
		//    = w
		Evaluable<PackedCollection<?>> dy = c.delta(x).get();
		PackedCollection<?> dout = dy.evaluate(v);
		dout.print();
	}
}
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Machine Learning

The system is gradually becoming fully-featured enough to support machine learning tasks.
An example of CNN training is shown below. In the example below the input and loss
computation are both random, so there isn't really anything useful being learned, but it
is a good example of how to use the framework for these kinds of workloads.

```Java
public class MyNativeEnabledApplication implements CodeFeatures {
	public static void main(String args[]) {
		new MyNativeEnabledApplication().trainCnn();
	}
	
	public void trainCnn() {
		int s = 10;

		Tensor<Double> t = new Tensor<>();
		shape(s, s).stream().forEach(pos -> t.insert(0.5 + 0.5 * Math.random(), pos));

		PackedCollection<?> input = t.pack();
		train(input, model(s, s, 3, 8, 10));
	}

	protected void train(PackedCollection<?> input, Model model) {
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
		model.addLayer(convolution2d(convSize, convFilters));
		model.addLayer(pool2d(2));
		model.addBlock(flatten());
		model.addLayer(dense(denseSize));
		model.addLayer(softmax());
		log("Created model (" + model.getBlocks().size() + " blocks)");
		return model;
	}
}
```

This functionality will be substantially improved during the remainder of 2024 prior to the release of
version 1.0.0.
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### What are the terms of the LICENSE?

Copyright 2024  Michael Murray

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

<p align="right">(<a href="#readme-top">back to top</a>)</p>


## Contact

Michael Murray - [@ashesfall](https://twitter.com/ashesfall) - michael@almostrealism.com

[![LinkedIn][linkedin-shield]][linkedin-url]

Original Project Link: [https://github.com/almostrealism/common](https://github.com/almostrealism/common)

<p align="right">(<a href="#readme-top">back to top</a>)</p>


[contributors-shield]: https://img.shields.io/github/contributors/almostrealism/common.svg?style=for-the-badge
[contributors-url]: https://github.com/almostrealism/common/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/almostrealism/common.svg?style=for-the-badge
[forks-url]: https://github.com/almostrealism/common/network/members
[stars-shield]: https://img.shields.io/github/stars/almostrealism/common.svg?style=for-the-badge
[stars-url]: https://github.com/almostrealism/common/stargazers
[issues-shield]: https://img.shields.io/github/issues/almostrealism/common.svg?style=for-the-badge
[issues-url]: https://github.com/almostrealism/common/issues
[license-shield]: https://img.shields.io/github/license/almostrealism/common.svg?style=for-the-badge
[license-url]: https://github.com/almostrealism/common/blob/master/LICENSE
[linkedin-shield]: https://img.shields.io/badge/-LinkedIn-black.svg?style=for-the-badge&logo=linkedin&colorB=555
[linkedin-url]: https://linkedin.com/in/ashesfall
