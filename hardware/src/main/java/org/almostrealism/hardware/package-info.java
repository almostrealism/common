/**
 * Hardware acceleration abstractions for executing computations on CPU and GPU.
 * <p>
 * The hardware module provides the core infrastructure for compiling and executing
 * computational graphs on various hardware backends including OpenCL, Metal, and native C.
 * It bridges the gap between high-level computation descriptions (Producers/Evaluables)
 * and efficient hardware execution.
 * </p>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li><b>Hardware Backend Management</b> - {@link org.almostrealism.hardware.HardwareOperator}
 *       provides access to hardware-accelerated computation execution</li>
 *   <li><b>Memory Management</b> - {@link org.almostrealism.hardware.MemoryData} and
 *       {@link org.almostrealism.hardware.MemoryBank} for efficient memory allocation</li>
 *   <li><b>Kernel Compilation</b> - {@link org.almostrealism.hardware.KernelizedOperation}
 *       compiles computation graphs to native code</li>
 *   <li><b>Computation Execution</b> - {@link org.almostrealism.hardware.ComputeContext}
 *       manages computation lifecycle and caching</li>
 *   <li><b>Input Handling</b> - {@link org.almostrealism.hardware.PassThroughProducer}
 *       for passing arguments through computation graphs</li>
 * </ul>
 *
 * <h2>Hardware Backends</h2>
 * <ul>
 *   <li><b>Native</b> - JNI-based C execution (default)</li>
 *   <li><b>OpenCL</b> - GPU/CPU acceleration via OpenCL</li>
 *   <li><b>Metal</b> - Apple Silicon GPU acceleration</li>
 *   <li><b>External</b> - Generated executable approach</li>
 * </ul>
 *
 * <h2>Environment Configuration</h2>
 * <p>
 * Hardware acceleration requires environment variables to be set:
 * </p>
 * <pre>{@code
 * export AR_HARDWARE_LIBS=/tmp/ar_libs/
 * export AR_HARDWARE_DRIVER=native  # or opencl, metal
 * }</pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Enable hardware acceleration
 * HardwareOperator.enableKernelLog = false;
 *
 * // Create computation
 * Producer<PackedCollection<?>> computation = vectorAdd(a, b);
 *
 * // Compile to hardware
 * Evaluable<PackedCollection<?>> kernel = computation.get();
 *
 * // Execute on hardware
 * PackedCollection<?> result = kernel.evaluate(arg1, arg2);
 * }</pre>
 *
 * <h2>PassThroughProducer Behavior</h2>
 * <p>
 * PassThroughProducer represents input arguments in computation graphs. Its behavior
 * depends on the TraversalPolicy:
 * </p>
 * <ul>
 *   <li><b>Fixed-count</b> (default) - Predetermined dimensions, fixed kernel size</li>
 *   <li><b>Variable-count</b> - Adapts to runtime input sizes, flexible kernel</li>
 * </ul>
 *
 * @see org.almostrealism.hardware.HardwareOperator
 * @see org.almostrealism.hardware.KernelizedOperation
 * @see org.almostrealism.hardware.ComputeContext
 * @see org.almostrealism.hardware.PassThroughProducer
 */
package org.almostrealism.hardware;
