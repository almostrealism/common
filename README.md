<a name="readme-top"></a>

[![Contributors][contributors-shield]][contributors-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![Apache License][license-shield]][license-url]
[![LinkedIn][linkedin-shield]][linkedin-url]


<h3 align="center">Almost Realism Scientific Computing and Machine Learning Libraries</h3>

  <p align="center">
    Tools for high-performance scientific computing, generative art, and machine learning in Java
    with pluggable native acceleration (currently implemented with OpenCL, though other backend
    acceleration libraries would be a welcome contribution from the community).
    <br />
    <a href="https://github.com/almostrealism/common/issues">Report Bug</a>
    ·
    <a href="https://github.com/almostrealism/common/issues">Request Feature</a>
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
makes it potentially faster than systems which are design to perform certain common operations
quickly, but are not capable of generating custom accelerator code.

Machine learning capabilities will be expanded substantially over the remainder of 2023, but
an early example of a neural network is provided at the end of this document.

Using this library correctly allows you to take complex operations, written in Java, and end
up with binaries for CPU, GPU, or FPGA that are as fast or faster than hand-written native code.

#### Support Accelerators
    1. Normal JNI Operations via runtime generated .so/.dylib
    2. External Native Operations via a generated executable
    3. OpenCL (JNI with JOCL) on CPU
    4. OpenCL (JNI with JOCL) on GPU

*For more information about Java bindings for OpenCL, visit jocl.org*
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Why would you want this?
When choosing Java as your target language, you are normally making a trade-off related to
leveraging native instruction sets. The assumption is normally that applications can benefit
from the JVM in a way that makes it worth sacrificing access to native instruction sets like
AVX2 or native frameworks like CL and Metal. The Almost Realism Libraries eliminate this trade
off, allowing you to create Java applications that make use of acceleration without breaking
the design patterns that are normally used in Java development.

This also means you can, for example, define ML workflows and other HPC processes in Scalar,
Kotlin or Groovy. If you've ever been frustrated trying to take a Python project to production,
this library should be able to make it unnecessary. You also can run your programs on any
machine with an OpenCL compatible device and a JVM, without compiling on the target system.
This means no more of the headache of "numpy failed to build on this Amazon Graviton machine",
etc.

### What does it depend on?
The dependency footprint is unbelievably small. The only dependency that is brought with
this library results from your choice of accelerator. To use JOCL you will need the native
bindings for CL. They are available from jocl.org. To use TensorFlow you will need native
bindings for TensorFlow. They are available from tensorflow.org. (Tensorflow support will
not be available until 2024).
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### To use the libraries

Add Maven Repository:

        <repositories>
                <repository>
                        <id>internal</id>
                        <name>Almost Realism/name>
                        <url>https://almostrealism.jfrog.io/artifactory/default-libs-release</url>
                        <releases><enabled>true</enabled></releases>
                        <snapshots><enabled>true</enabled></snapshots>
                </repository>
        </repositories>

Add utils:

        <dependency>
            <groupId>org.almostrealism</groupId>
            <artifactId>ar-utils</artifactId>
            <version>0.52</version>
        </dependency>

### Enabling Your Application

All of the library functionality is provided as default methods of an interface called
**CodeFeatures**

```Java
    public class MyNativeEnableApplication implements CodeFeatures {
	    public static void main(String args[]) {
			new MyNativeEnableApplication().performMath();
		}
		
		public void performMath() {
			// .. include native operations here
		}
    }
```

Simple mathematical operations on constant values are compact to express:
```Java
public class MyNativeEnableApplication implements CodeFeatures {
	// ....

	public void performMath() {
		// Compose the expression
		Supplier<Evaluable<Scalar>> constantOperation = v(1.0).multiply(v(2.0));
		
		// Compile the expression
		Evaluable<Scalar> compiledOperation = constantOperation.get();
		
		// Evaluate the expression
		System.out.println("1 * 2 = " + constantOperation.evaluate());
	}
}
```

When the expression is compiled it will be converted to the target accelerator. This might
be an OpenCL kernel program, an entirely separate native process or library, or a TensorFlow
graph. You can write your entire application this way without having to decide which one to
use, or you can use different ones in different places - leveraging Metal on MacOS, while
using a native lib on windows and a TensorFlow graph in the cloud. All with the same language
for defining your expressions.
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Tutorial

### Using Tensor

Tensor is a data structure that is used to represent multi-dimensional arrays of data. Before
a tensor can be used in computations, it has to be packed (at which point it's shape becomes
immutable).

```Java
public class MyNativeEnableApplication implements CodeFeatures {
	// ....

	public void createTensor() {
		// Create the tensor
		Tensor<Double> t = new Tensor<>();
		t.insert(1.0, 0, 0);
		t.insert(2.0, 0, 1);
		t.insert(3.0, 0, 2);
		t.insert(4.0, 1, 0);
		t.insert(5.0, 1, 1);
		t.insert(6.0, 1, 2);
		
		Tensor<Double> p = new Tensor<>();
		p.insert(3.0, 0, 0);
		p.insert(4.0, 0, 1);
		
		// Prepare the computation
        CollectionProducer<PackedCollection<?>> product = t.pack().multiply(p.pack());
		
		// Compile the computation and evaluate it
		PackedCollection<?> out = product.get().evaluate();
	}
}
```

#### Using Variables
Mathematical operations can use both constant values and variable values:
```Java
public class MyNativeEnableApplication implements CodeFeatures {
	// ....

	public void performMath() {
		// Define argument 0
        Producer<Scalar> arg = v(Scalar.class, 0);
		
		// Compose the expression
		Supplier<Evaluable<Scalar>> constantOperation = v(7.0).multiply(arg);
		
		// Compile the expression
		Evaluable<Scalar> compiledOperation = constantOperation.get();
		
		// Evaluate the expression repeatedly
		System.out.println("7 * 3 = " + constantOperation.evaluate(new Scalar(3)));
		System.out.println("7 * 4 = " + constantOperation.evaluate(new Scalar(4)));
		System.out.println("7 * 5 = " + constantOperation.evaluate(new Scalar(5)));
	}
}
```

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
public class MyNativeEnableApplication implements CodeFeatures {
	// ....

	public void performThreeExperiments() {
		for (int i = 0; i < 3; i++) {
			dc(() -> {
				// Define argument 0
				Producer<Scalar> arg = v(Scalar.class, 0);

				// Compose the expression
				Supplier<Evaluable<Scalar>> constantOperation = v(7.0).multiply(arg);

				// Compile the expression
				Evaluable<Scalar> compiledOperation = constantOperation.get();

				// Evaluate the expression repeatedly
				System.out.println("7 * 3 = " + constantOperation.evaluate(new Scalar(3)));
				System.out.println("7 * 4 = " + constantOperation.evaluate(new Scalar(4)));
				System.out.println("7 * 5 = " + constantOperation.evaluate(new Scalar(5)));
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
across different **ComputeContext**s (though obviously not every kind of **ComputeContext**
can be used with every kind of memory: if you are using **CLDataContext** and memory is stored
on a GPU device, there is no way to use it with a **ExternalNativeComputeContext**, for
example).

**ComputeContexts** are global to the **Thread**, a JVM can have multiple **ComputeContexts**
at once, if multiple **Thread**s were used to create them.

When creating a **ComputeContext**, you can instruct the **DataContext** of your expectations,
and it will make a best effort to fulfill them. It will not fail when your expectations cannot
be met, it will just provide something other than what you expected.

```Java
public class MyNativeEnableApplication implements CodeFeatures {
	// ....

	public void useJniCLandJniC() {
		dc(() -> {
			Scalar result = new Scalar();

			ScalarProducer sum = scalarAdd(v(1.0), v(2.0));
			ScalarProducer product = scalarsMultiply(v(3.0), v(2.0));

			cc(() -> a(2, p(result), sum).get().run(), ComputeRequirement.CL);
			System.out.println("Result = " + result.getValue());

			cc(() -> a(2, p(result), product).get().run(), ComputeRequirement.C);
			System.out.println("Result = " + result.getValue());
		});
	}
}
```

In this example, we will perform addition using OpenCL, but we will perform multiplication
using a regular JNI method in a runtime-generated .so or .dylib.
<p align="right">(<a href="#readme-top">back to top</a>)</p>

#### Parallelization via Accelerator
Although you can use the tool with multiple threads (compiled operations are threadsafe),
you may want to leverage parallelization that cannot be accomplished with Java's Thread
concept. If you are targeting a GPU with OpenCL, for example, you'll want to express a
collection of operations with a single operation. This works the same way.

```Java
public class MyNativeEnableApplication implements CodeFeatures {
	// ....

	public void performMath() {
		// Define argument 0
        Producer<Scalar> arg = v(Scalar.class, 0);
		
		// Compose the expression
		Supplier<Evaluable<Scalar>> constantOperation = v(7.0).multiply(arg);
		
		// Compile the expression
		Evaluable<Scalar> compiledOperation = constantOperation.get();
		
		ScalarBank bank = new ScalarBank(3);
		bank.set(0, 3.0);
		bank.set(1, 4.0);
		bank.set(2, 5.0);
		
		ScalarBank results = new ScalarBank(3);
		
		// Evaluate the expression with the accelerator deciding how to parallelize it
        compiledOperation.into(results).evaluate(bank);
		
		System.out.println("7 * 3 = " + results.get(0));
		System.out.println("7 * 4 = " + results.get(1));
		System.out.println("7 * 5 = " + results.get(2));
	}
}
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Machine Learning (Alpha)

The system is gradually becoming fully-featured enough to support machine learning tasks. A convenient
method for defining ML models is to use Groovy. An example of a CNN is shown below.

```Groovy
import org.almostrealism.Ops;
import org.almostrealism.model.Model;
import org.almostrealism.collect.PackedCollection;

def ml = Ops.ops()

def shape = ml.shape(100, 100)
def model = new Model(shape)
model.addLayer(ml.convolution2d(3, 8))
model.addLayer(ml.pool2d(2))
model.addBlock(ml.flatten())
model.addLayer(ml.dense(10))
model.addLayer(ml.softmax())

def input = new PackedCollection(shape)
model.setup().get().run()
output = model.forward(input)

def gradient = computeGradient(input, output)
model.backward(gradient)
```

This functionality will be substantially improved during the remainder of 2023 prior to the release of
version 1.0.0.
<p align="right">(<a href="#readme-top">back to top</a>)</p>

### What are the terms of the LICENSE?

Copyright 2023  Michael Murray

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

Project Link: [https://github.com/almostrealism/common](https://github.com/almostrealism/common)

<p align="right">(<a href="#readme-top">back to top</a>)</p>


<!-- MARKDOWN LINKS & IMAGES -->
<!-- https://www.markdownguide.org/basic-syntax/#reference-style-links -->
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
