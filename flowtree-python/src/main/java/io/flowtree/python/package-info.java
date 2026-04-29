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

/**
 * Jython (Python-on-JVM) job execution support for the FlowTree workflow engine.
 *
 * <p>This package bridges FlowTree's job scheduling infrastructure with Jython,
 * allowing Python scripts to be submitted, distributed, and executed as FlowTree
 * {@code Job} instances:</p>
 *
 * <ul>
 *   <li>{@link io.flowtree.python.JythonJob} — a {@code Job} implementation that
 *       decodes a Base64-encoded Jython script and executes it inside a
 *       {@link org.python.util.PythonInterpreter}. Thread-local interpreter instances
 *       are maintained for repeated script execution within the same thread.</li>
 *   <li>{@link io.flowtree.python.JythonJob.Factory} — an {@code AbstractJobFactory}
 *       that produces {@code JythonJob} instances from an encoded script, enabling
 *       the standard FlowTree job dispatch and retry cycle.</li>
 * </ul>
 */
package io.flowtree.python;
