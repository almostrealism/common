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

/**
 * Neuron models and the inputs that drive them. Each type is a {@link org.almostrealism.model.Block}
 * that owns its device-resident state and builds one time step as a computation graph, so it can
 * be placed in a pipe by PDSL or by Java and stepped by a {@code SimulationRunner}.
 */
package org.almostrealism.biology.neuron;
