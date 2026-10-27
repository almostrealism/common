/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.dsl;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * A set of PDSL primitives that a module above the language core contributes to every
 * {@link PdslInterpreter}, discovered at runtime.
 *
 * <p>The language core holds only domain-agnostic built-ins. A module whose primitives belong in
 * every program that can see that module (the attention and rotary position primitives of the
 * machine-learning engine, for example) implements this interface and names the implementation in
 * {@code META-INF/services/org.almostrealism.dsl.PdslExtension}. Every interpreter applies every
 * extension on the classpath when it is constructed, so a program can call those primitives
 * whenever the module that defines them is present, with no registration by the caller.</p>
 *
 * <p>Primitives that a caller opts into for a particular program, such as the audio primitives,
 * are supplied through a {@link PdslLoader} registrar instead.</p>
 */
public interface PdslExtension {

	/**
	 * Registers this extension's primitives with an interpreter.
	 *
	 * @param interpreter the interpreter to configure, before any layer or model is built
	 */
	void registerWith(PdslInterpreter interpreter);

	/**
	 * Returns every extension on the classpath, in the order {@link ServiceLoader} discovers them.
	 *
	 * @return the discovered extensions
	 */
	static List<PdslExtension> discover() {
		return ServiceLoader.load(PdslExtension.class).stream()
				.map(ServiceLoader.Provider::get)
				.collect(Collectors.toList());
	}
}
