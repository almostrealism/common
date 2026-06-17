/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.dsl;

import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.time.TemporalFeatures;

/**
 * Mixin type giving the PDSL interpreter and its built-in function library access
 * to the framework feature default methods through one shared instance.
 */
class PdslFeatures implements AttentionFeatures, RotationFeatures, TemporalFeatures {

	/** The shared instance used throughout the PDSL implementation. */
	static final PdslFeatures INSTANCE = new PdslFeatures();

	/** Only the shared {@link #INSTANCE} is used. */
	private PdslFeatures() { }
}
