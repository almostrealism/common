/*
 * Copyright 2016 Michael Murray
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
package io.almostrealism.uml;

import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Target;

/**
 * A marker annotation for types that hold significant state which may be expensive to compute.
 *
 * <p>This annotation identifies types that represent persistent or stateful entities whose
 * data is valuable enough to warrant special handling such as caching, persistence, or
 * careful lifecycle management. These are typically domain model objects, trained machine
 * learning models, or other data structures that are costly to create or reconstruct.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code @ModelEntity} is used to mark types that:</p>
 * <ul>
 *   <li><strong>Hold Valuable State:</strong> Contain data that is expensive to compute or acquire</li>
 *   <li><strong>Require Persistence:</strong> May need to be saved to disk or database</li>
 *   <li><strong>Benefit from Caching:</strong> Should be reused rather than recreated</li>
 *   <li><strong>Have Complex Lifecycle:</strong> Need careful initialization and cleanup</li>
 * </ul>
 *
 * <h2>Characteristics of Model Entities</h2>
 * <p>Types marked with {@code @ModelEntity} typically exhibit:</p>
 * <ul>
 *   <li><strong>Statefulness:</strong> Maintain significant internal state</li>
 *   <li><strong>Expensive Construction:</strong> Costly to create from scratch</li>
 *   <li><strong>Persistent Identity:</strong> Have meaningful identity beyond object reference</li>
 *   <li><strong>Serialization Needs:</strong> Often require save/load capabilities</li>
 *   <li><strong>Lifecycle Management:</strong> May implement {@link io.almostrealism.lifecycle.Destroyable}</li>
 * </ul>
 *
 * <h2>Common Use Cases</h2>
 *
 * <p><strong>Trained machine learning models:</strong></p>
 * <pre>{@code
 * @ModelEntity
 * public class NeuralNetwork {
 *     private final List<Layer> layers;
 *     private final PackedCollection<?>[] weights;
 *
 *     // Expensive: trained over hours/days
 *     public void train(Dataset data, int epochs) { ... }
 *
 *     // Persistence: save trained weights
 *     public void saveWeights(Path path) { ... }
 *     public void loadWeights(Path path) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Large cached computational results:</strong></p>
 * <pre>{@code
 * @ModelEntity
 * public class PrecomputedFrequencies {
 *     private final PackedCollection<?> frequencyCis;
 *     private final int maxSeqLength;
 *
 *     // Expensive: computed once and cached
 *     public PrecomputedFrequencies(int dim, int maxSeqLength, double theta) {
 *         this.maxSeqLength = maxSeqLength;
 *         this.frequencyCis = computeFrequencies(dim, maxSeqLength, theta);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Domain model with persistence:</strong></p>
 * <pre>{@code
 * @ModelEntity
 * public class Simulation implements Destroyable {
 *     @Named
 *     private String simulationId;
 *     private final List<Particle> particles;
 *     private final PhysicsEngine engine;
 *     private int stepCount;
 *
 *     // Save simulation state to disk
 *     public void checkpoint(Path path) { ... }
 *
 *     // Restore from checkpoint
 *     public static Simulation restore(Path path) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Weight tensors for model layers:</strong></p>
 * <pre>{@code
 * @ModelEntity
 * public class LayerWeights {
 *     private final PackedCollection<?> weights;
 *     private final PackedCollection<?> biases;
 *     private final String layerName;
 *
 *     // Loaded from checkpoint file
 *     public static LayerWeights load(StateDictionary dict, String layerName) {
 *         return new LayerWeights(
 *             dict.get(layerName + ".weight"),
 *             dict.get(layerName + ".bias"),
 *             layerName
 *         );
 *     }
 * }
 * }</pre>
 *
 * <h2>ModelEntity vs Other Annotations</h2>
 * <table>
 *   <tr>
 *     <th>Annotation</th>
 *     <th>State</th>
 *     <th>Typical Use</th>
 *     <th>Lifecycle</th>
 *   </tr>
 *   <tr>
 *     <td>@ModelEntity</td>
 *     <td>Significant, expensive</td>
 *     <td>Domain models, trained models</td>
 *     <td>Long-lived, persistent</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Stateless @Stateless}</td>
 *     <td>None or minimal</td>
 *     <td>Pure functions, utilities</td>
 *     <td>Short-lived or stateless</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Function @Function}</td>
 *     <td>Configuration only</td>
 *     <td>Evaluable computations</td>
 *     <td>Reusable computation</td>
 *   </tr>
 * </table>
 *
 * <h2>Framework Integration</h2>
 * <p>In the Almost Realism framework, {@code @ModelEntity} types often:</p>
 * <ul>
 *   <li><strong>Implement {@link io.almostrealism.lifecycle.Destroyable}:</strong> For GPU memory cleanup</li>
 *   <li><strong>Use {@link Named}:</strong> For identification and persistence</li>
 *   <li><strong>Support Serialization:</strong> Via StateDictionary or custom formats</li>
 *   <li><strong>Integrate with Caching:</strong> Cached by signature or name</li>
 *   <li><strong>Manage GPU Resources:</strong> Allocate/deallocate hardware memory</li>
 * </ul>
 *
 * <h2>Design Considerations</h2>
 * <p>When creating {@code @ModelEntity} types, consider:</p>
 * <ul>
 *   <li><strong>Lazy Initialization:</strong> Defer expensive computation until needed</li>
 *   <li><strong>Resource Management:</strong> Implement Destroyable if holding GPU/native resources</li>
 *   <li><strong>Serialization Strategy:</strong> How will state be saved and restored?</li>
 *   <li><strong>Identity:</strong> What makes two instances "the same"? Consider implementing Signature</li>
 *   <li><strong>Thread Safety:</strong> Will instances be shared across threads?</li>
 *   <li><strong>Lifecycle Hooks:</strong> Does initialization/cleanup require special handling?</li>
 * </ul>
 *
 * <h2>Best Practices</h2>
 * <ul>
 *   <li>Use {@code @ModelEntity} for types whose state is expensive to create</li>
 *   <li>Implement persistence mechanisms (save/load) for valuable state</li>
 *   <li>Consider implementing {@link Signature} for identity-based caching</li>
 *   <li>Implement {@link io.almostrealism.lifecycle.Destroyable} if managing resources</li>
 *   <li>Document the cost of creating instances and recommended lifecycle</li>
 *   <li>Avoid overuse - not every class with state needs this annotation</li>
 * </ul>
 *
 * <h2>Annotation Properties</h2>
 * <p>This is a marker annotation with no attributes. Its presence conveys that the type
 * holds significant, expensive-to-compute state. It applies to types (classes, interfaces)
 * via {@code @Target(TYPE)}.</p>
 *
 * @see Function
 * @see Stateless
 * @see io.almostrealism.lifecycle.Destroyable
 * @see Signature
 * @see Named
 * @author Michael Murray
 */
@Target(TYPE)
public @interface ModelEntity {

}
