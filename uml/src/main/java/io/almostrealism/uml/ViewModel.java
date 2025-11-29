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

import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

/**
 * A marker annotation for types that serve as view models in presentation logic.
 *
 * <p>This annotation identifies types that act as intermediaries between domain models
 * and user interfaces, following the Model-View-ViewModel (MVVM) or Model-View-Controller
 * (MVC) architectural patterns. View models handle presentation-specific logic, UI state
 * management, and data transformation for display purposes.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code @ViewModel} is used to mark types that:</p>
 * <ul>
 *   <li><strong>Mediate UI and Domain:</strong> Bridge between domain models and views</li>
 *   <li><strong>Presentation Logic:</strong> Contain UI-specific behavior and state</li>
 *   <li><strong>Data Transformation:</strong> Format domain data for display</li>
 *   <li><strong>UI State Management:</strong> Track view-specific state (selections, filters, etc.)</li>
 * </ul>
 *
 * <h2>Characteristics of View Models</h2>
 * <p>Types marked with {@code @ViewModel} typically exhibit:</p>
 * <ul>
 *   <li><strong>Presentation State:</strong> Hold UI-specific state separate from domain state</li>
 *   <li><strong>Data Formatting:</strong> Transform domain data for user-friendly display</li>
 *   <li><strong>Command Handling:</strong> Process user interactions and commands</li>
 *   <li><strong>Validation Logic:</strong> Perform UI-level validation before domain updates</li>
 *   <li><strong>Observable Properties:</strong> Often notify views of state changes</li>
 * </ul>
 *
 * <h2>Common Use Cases</h2>
 *
 * <p><strong>Visualization control for simulations:</strong></p>
 * <pre>{@code
 * @ViewModel
 * public class SimulationViewModel {
 *     private final Simulation simulation;  // Domain model
 *     private boolean paused = false;
 *     private double playbackSpeed = 1.0;
 *     private ViewportSettings viewport;
 *
 *     // Presentation logic
 *     public void togglePause() {
 *         paused = !paused;
 *         if (paused) {
 *             simulation.pause();
 *         } else {
 *             simulation.resume();
 *         }
 *     }
 *
 *     // Data transformation for display
 *     public String getStatusText() {
 *         return String.format("Step: %d | FPS: %.1f | Particles: %d",
 *             simulation.getStepCount(),
 *             simulation.getFPS(),
 *             simulation.getParticleCount());
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Data visualization configuration:</strong></p>
 * <pre>{@code
 * @ViewModel
 * public class ChartViewModel {
 *     private final List<DataSeries> dataSeries;  // Domain data
 *     private ChartType chartType = ChartType.LINE;
 *     private String title;
 *     private AxisScale xAxisScale = AxisScale.LINEAR;
 *     private AxisScale yAxisScale = AxisScale.LINEAR;
 *
 *     // Transform data for chart rendering
 *     public ChartData getChartData() {
 *         return ChartData.builder()
 *             .type(chartType)
 *             .series(dataSeries.stream()
 *                 .map(this::transformSeries)
 *                 .collect(Collectors.toList()))
 *             .build();
 *     }
 *
 *     // UI state management
 *     public void setAxisScale(Axis axis, AxisScale scale) {
 *         if (axis == Axis.X) xAxisScale = scale;
 *         else yAxisScale = scale;
 *         notifyViewChanged();
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Model configuration UI:</strong></p>
 * <pre>{@code
 * @ViewModel
 * public class ModelConfigViewModel {
 *     private final ModelConfig config;  // Domain configuration
 *     private ValidationErrors errors = new ValidationErrors();
 *
 *     // UI-friendly property access
 *     public String getLearningRateText() {
 *         return String.format("%.6f", config.getLearningRate());
 *     }
 *
 *     // Validation before applying to domain
 *     public boolean applyLearningRate(String text) {
 *         try {
 *             double lr = Double.parseDouble(text);
 *             if (lr <= 0 || lr > 1.0) {
 *                 errors.add("Learning rate must be between 0 and 1");
 *                 return false;
 *             }
 *             config.setLearningRate(lr);
 *             errors.clear();
 *             return true;
 *         } catch (NumberFormatException e) {
 *             errors.add("Invalid number format");
 *             return false;
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Rendering pipeline controller:</strong></p>
 * <pre>{@code
 * @ViewModel
 * public class RenderViewModel implements Lifecycle {
 *     private final Scene scene;  // Domain model
 *     private Camera camera;
 *     private LightingSettings lighting;
 *     private RenderQuality quality = RenderQuality.MEDIUM;
 *
 *     // Command handlers
 *     public void setQualityPreset(RenderQuality quality) {
 *         this.quality = quality;
 *         applyQualitySettings(quality);
 *     }
 *
 *     // Data preparation for rendering
 *     public RenderContext prepareRenderContext() {
 *         return new RenderContext(
 *             scene.getVisibleObjects(camera.getFrustum()),
 *             camera.getViewMatrix(),
 *             lighting.getActiveLights(),
 *             quality.getSettings()
 *         );
 *     }
 *
 *     @Override
 *     public void reset() {
 *         camera.resetToDefault();
 *         lighting.resetToDefaults();
 *         quality = RenderQuality.MEDIUM;
 *     }
 * }
 * }</pre>
 *
 * <h2>ViewModel vs ModelEntity</h2>
 * <table>
 * <caption>Table</caption>
 *   <tr>
 *     <th>Aspect</th>
 *     <th>@ViewModel</th>
 *     <th>@ModelEntity</th>
 *   </tr>
 *   <tr>
 *     <td>Purpose</td>
 *     <td>Presentation logic and UI state</td>
 *     <td>Domain logic and business state</td>
 *   </tr>
 *   <tr>
 *     <td>State</td>
 *     <td>View-specific (filters, selections, display settings)</td>
 *     <td>Domain-specific (entity data, business rules)</td>
 *   </tr>
 *   <tr>
 *     <td>Persistence</td>
 *     <td>Usually transient (UI preferences may persist)</td>
 *     <td>Typically persisted (database, checkpoints)</td>
 *   </tr>
 *   <tr>
 *     <td>Lifecycle</td>
 *     <td>Tied to UI/view lifecycle</td>
 *     <td>Tied to domain entity lifecycle</td>
 *   </tr>
 *   <tr>
 *     <td>Examples</td>
 *     <td>ChartViewModel, SimulationControls</td>
 *     <td>NeuralNetwork, Simulation, LayerWeights</td>
 *   </tr>
 * </table>
 *
 * <h2>Architectural Benefits</h2>
 * <p>Using {@code @ViewModel} supports:</p>
 * <ul>
 *   <li><strong>Separation of Concerns:</strong> UI logic separate from domain logic</li>
 *   <li><strong>Testability:</strong> Test presentation logic without UI framework</li>
 *   <li><strong>Reusability:</strong> Same domain model with different view models</li>
 *   <li><strong>Maintainability:</strong> UI changes don't affect domain model</li>
 *   <li><strong>Independence:</strong> View models can be developed independently of views</li>
 * </ul>
 *
 * <h2>Framework Integration</h2>
 * <p>In the Almost Realism framework, {@code @ViewModel} types may:</p>
 * <ul>
 *   <li><strong>Implement {@link io.almostrealism.lifecycle.Lifecycle}:</strong> For reset/cleanup</li>
 *   <li><strong>Use {@link Named}:</strong> For identification in UI hierarchies</li>
 *   <li><strong>Delegate to Domain Models:</strong> Forward commands to {@link ModelEntity} types</li>
 *   <li><strong>Manage Display State:</strong> Track zoom, pan, selections, filters</li>
 *   <li><strong>Format Data:</strong> Transform domain data for visualization</li>
 * </ul>
 *
 * <h2>Design Considerations</h2>
 * <p>When creating {@code @ViewModel} types:</p>
 * <ul>
 *   <li><strong>Keep Domain-Free:</strong> Don't embed business logic in view models</li>
 *   <li><strong>Delegate to Domain:</strong> Forward complex operations to domain models</li>
 *   <li><strong>Manage UI State:</strong> Track view-specific state separate from domain</li>
 *   <li><strong>Validation:</strong> Perform input validation before updating domain</li>
 *   <li><strong>Observability:</strong> Consider notification mechanisms for view updates</li>
 *   <li><strong>Lifecycle:</strong> Clean up resources when view is destroyed</li>
 * </ul>
 *
 * <h2>Best Practices</h2>
 * <ul>
 *   <li>Keep view models focused on presentation concerns only</li>
 *   <li>Delegate business logic to domain models ({@link ModelEntity})</li>
 *   <li>Transform domain data for display rather than exposing raw domain objects</li>
 *   <li>Implement {@link io.almostrealism.lifecycle.Lifecycle} for cleanup</li>
 *   <li>Use {@link Named} for identifying view models in hierarchies</li>
 *   <li>Document the relationship between view model and domain model</li>
 * </ul>
 *
 * <h2>Annotation Properties</h2>
 * <p>This is a marker annotation with no attributes. Its presence indicates that the type
 * serves as a view model for presentation logic. It applies to types (classes) via
 * {@code @Target(TYPE)}.</p>
 *
 * @see ModelEntity
 * @see Named
 * @see io.almostrealism.lifecycle.Lifecycle
 * @author Michael Murray
 */
@Target(TYPE)
public @interface ViewModel {

}
