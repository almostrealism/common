/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio;

import io.almostrealism.cycle.Setup;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.line.AudioLineOperation;
import org.almostrealism.audio.line.BufferedAudio;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.InputLine;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.SharedMemoryAudioLine;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.graph.temporal.DefaultWaveCellData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Gene;
import org.almostrealism.time.Temporal;
import org.almostrealism.time.TemporalList;
import org.almostrealism.time.TemporalRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * A hierarchical container for audio processing cells with fluent API support.
 *
 * <p>{@code CellList} extends {@code ArrayList} to hold {@link Cell} instances and provides
 * methods for building complex audio processing chains. It manages cell lifecycle
 * including setup, execution, and teardown phases.</p>
 *
 * <h2>Core Architecture</h2>
 *
 * <p>{@code CellList} uses a <strong>push-based data flow</strong> model with hierarchical
 * organization:</p>
 *
 * <pre>
 * CellList Hierarchy:
 *
 *     Parent CellList(s)  --  tick FIRST
 *           |
 *     Current CellList    --  tick SECOND
 *           |
 *     Requirements        --  tick LAST
 * </pre>
 *
 * <p><strong>Key Fields:</strong></p>
 * <ul>
 *   <li>{@code parents} - Parent CellLists whose temporals tick before this list's</li>
 *   <li>{@code roots} - Entry points that receive initial push signals</li>
 *   <li>{@code setups} - Setup operations to run during initialization</li>
 *   <li>{@code requirements} - Temporals that tick after this list's cells</li>
 *   <li>{@code finals} - Cleanup callbacks to run during reset</li>
 *   <li>{@code data} - PackedCollections for lifecycle management</li>
 * </ul>
 *
 * <h2>Tick Ordering - CRITICAL</h2>
 *
 * <p>Understanding tick order is essential for correct pipeline behavior. The order is
 * determined by {@link #getAllTemporals()}:</p>
 *
 * <ol>
 *   <li><strong>Parents' temporals</strong> - Collected recursively, depth-first</li>
 *   <li><strong>This list's cells</strong> - Cells that implement {@link Temporal}</li>
 *   <li><strong>Requirements</strong> - Added via {@link #addRequirement(Temporal)}</li>
 * </ol>
 *
 * <p><strong>Important:</strong> If you want something to tick BEFORE the current list's
 * cells, put it in a parent CellList. Do NOT create custom mechanisms for pre-tick
 * operations - use the parent hierarchy.</p>
 *
 * <pre>{@code
 * // CORRECT: Use parent hierarchy for ordering
 * CellList renderList = new CellList();
 * renderList.add(renderCell);  // Ticks first
 *
 * CellList outputList = new CellList(renderList);
 * outputList.add(outputCell);  // Ticks after renderCell
 * }</pre>
 *
 * <h2>Fluent API</h2>
 *
 * <p>{@code CellList} supports method chaining for building processing pipelines.
 * Each fluent method creates a new {@code CellList} with the current list as parent:</p>
 *
 * <pre>{@code
 * CellList cells = w(0, "input.wav")     // Create wave cell (base CellList)
 *     .d(i -> _250ms())                   // Add 250ms delay (child CellList)
 *     .f(i -> hp(c(500), scalar(0.1)))   // Add high-pass filter (child)
 *     .m(i -> scale(0.8))                 // Apply 80% volume (child)
 *     .o(i -> new File("output.wav"));   // Set output (child)
 *
 * cells.sec(10).get().run();             // Process 10 seconds
 * }</pre>
 *
 * <p>This creates a parent chain: wave -&gt; delay -&gt; filter -&gt; scale -&gt; output</p>
 * <p>Tick order follows the parent chain: wave ticks first, output ticks last.</p>
 *
 * <h2>Key Methods</h2>
 *
 * <h3>Signal Processing</h3>
 * <ul>
 *   <li>{@link #d(IntFunction)} - Add delay to each cell</li>
 *   <li>{@link #f(IntFunction)} - Add filter to each cell</li>
 *   <li>{@link #m(IntFunction)} - Apply mixing/scaling factor</li>
 * </ul>
 *
 * <h3>Routing</h3>
 * <ul>
 *   <li>{@link #map(IntFunction)} - Map cells to destinations</li>
 *   <li>{@link #and(CellList)} - Combine with another CellList</li>
 *   <li>{@link #sum()} - Sum all cell outputs</li>
 *   <li>{@link #branch(IntFunction[])} - Create parallel branches</li>
 *   <li>{@link #gr(double, int, IntUnaryOperator)} - Create processing grid</li>
 * </ul>
 *
 * <h3>Output</h3>
 * <ul>
 *   <li>{@link #o(IntFunction)} - Set output file destination</li>
 *   <li>{@link #om(IntFunction)} - Set mono output file</li>
 *   <li>{@link #csv(IntFunction)} - Output as CSV</li>
 * </ul>
 *
 * <h3>Execution</h3>
 * <ul>
 *   <li>{@code sec(double)} - Get operation for processing N seconds</li>
 *   <li>{@code min(double)} - Get operation for processing N minutes</li>
 *   <li>{@code iter(int)} - Get operation for N iterations</li>
 * </ul>
 *
 * <h3>Real-Time Streaming</h3>
 * <ul>
 *   <li>{@link #buffer(String)} - Create BufferedOutputScheduler for shared memory</li>
 *   <li>{@link #buffer(BufferedAudio)} - Create scheduler for audio line</li>
 *   <li>{@link #buffer(Producer)} - Create TemporalRunner to destination</li>
 *   <li>{@link #toLineOperation()} - Convert to AudioLineOperation</li>
 * </ul>
 *
 * <h2>Lifecycle Management</h2>
 *
 * <h3>Setup Phase</h3>
 * <p>{@link #setup()} collects and executes all setup operations:</p>
 * <ol>
 *   <li>Parents' setups (recursively)</li>
 *   <li>Cells implementing {@link io.almostrealism.cycle.Setup}</li>
 *   <li>Requirements implementing Setup</li>
 *   <li>Explicit setups from {@link #addSetup(Setup)}</li>
 * </ol>
 *
 * <h3>Execution Phase</h3>
 * <p>{@link #tick()} creates the execution operation:</p>
 * <ol>
 *   <li>Push to all roots (triggers data flow)</li>
 *   <li>Tick all temporals (in order from {@link #getAllTemporals()})</li>
 * </ol>
 *
 * <h3>Reset Phase</h3>
 * <p>{@link #reset()} cleans up state:</p>
 * <ol>
 *   <li>Run finals callbacks</li>
 *   <li>Reset parents</li>
 *   <li>Reset all cells</li>
 *   <li>Reset requirements</li>
 * </ol>
 *
 * <h3>Destroy Phase</h3>
 * <p>{@link #destroy()} releases all resources:</p>
 * <ol>
 *   <li>Destroy tracked data collections</li>
 *   <li>Destroy parents</li>
 *   <li>Destroy cells implementing {@link Destroyable}</li>
 * </ol>
 *
 * <h2>Real-Time Audio Example</h2>
 *
 * <pre>{@code
 * // Create processing pipeline
 * CellList pipeline = w(0, "music.wav")
 *     .f(i -> hp(c(80), scalar(0.1)))
 *     .m(i -> scale(0.8));
 *
 * // Create scheduler for real-time output
 * BufferedOutputScheduler scheduler = pipeline.buffer("/dev/shm/audio");
 * scheduler.start();
 *
 * // Play for 60 seconds
 * Thread.sleep(60000);
 *
 * // Cleanup
 * scheduler.stop();
 * pipeline.destroy();
 * }</pre>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Parallel Processing</h3>
 * <pre>{@code
 * CellList drums = w(0, "drums.wav");
 * CellList synth = w(0, "synth.wav");
 * CellList mixed = drums.and(synth).sum();
 * }</pre>
 *
 * <h3>Dependency Ordering</h3>
 * <pre>{@code
 * // Make sure render happens before output
 * CellList render = new CellList();
 * render.add(renderCell);
 *
 * CellList output = new CellList(render);  // render is parent
 * output.add(outputCell);
 * // renderCell.tick() before outputCell.tick()
 * }</pre>
 *
 * <h3>Post-Processing</h3>
 * <pre>{@code
 * CellList main = w(0, "input.wav").m(i -> scale(0.8));
 * main.addRequirement(postProcessor);  // Ticks after all cells
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>{@code CellList} is not thread-safe. For concurrent access, use external
 * synchronization or create separate instances per thread.</p>
 *
 * @see CellFeatures
 * @see Cell
 * @see Temporal
 * @see BufferedOutputScheduler
 * @see TemporalRunner
 * @author Michael Murray
 */
public class CellList extends ArrayList<Cell<PackedCollection>> implements Cells, Destroyable {
	private final List<CellList> parents;
	private final List<Receptor<PackedCollection>> roots;
	private final List<Setup> setups;
	private final TemporalList requirements;
	private final List<Runnable> finals;

	private List<PackedCollection> data;

	/**
	 * Creates a new CellList with the specified parent CellLists.
	 *
	 * <p>Parents' temporals tick BEFORE this list's cells. This is the primary
	 * mechanism for controlling execution order.</p>
	 *
	 * @param parents CellLists whose temporals should tick before this list's
	 */
	public CellList(CellList... parents) {
		this(Arrays.asList(parents));
	}

	/**
	 * Creates a new CellList with the specified parent CellLists.
	 *
	 * <p>Parents' temporals tick BEFORE this list's cells. This is the primary
	 * mechanism for controlling execution order.</p>
	 *
	 * @param parents list of CellLists whose temporals should tick before this list's
	 */
	public CellList(List<CellList> parents) {
		this.parents = parents;
		this.roots = new ArrayList<>();
		this.setups = new ArrayList<>();
		this.requirements = new TemporalList();
		this.finals = new ArrayList<>();
	}

	/**
	 * Adds a setup operation to be executed during initialization.
	 *
	 * <p>Setup operations are executed in collection order during {@link #setup()}.</p>
	 *
	 * @param setup the setup operation to add
	 * @return this CellList for method chaining
	 */
	public CellList addSetup(Setup setup) { this.setups.add(setup); return this; }

	/**
	 * Adds a cell as both a root (entry point) and a regular cell.
	 *
	 * <p>Root cells receive initial push signals during {@link #tick()}. The push
	 * triggers data flow through the cell graph.</p>
	 *
	 * @param c the cell to add as a root
	 */
	public void addRoot(Cell<PackedCollection> c) {
		roots.add(c);
		add(c);
	}

	/**
	 * Adds a temporal requirement that ticks AFTER this list's cells.
	 *
	 * <p>Use this for post-processing operations that depend on the main cells
	 * completing their tick first.</p>
	 *
	 * @param t the temporal to add as a requirement
	 * @return this CellList for method chaining
	 */
	public CellList addRequirement(Temporal t) {
		requirements.add(t);
		return this;
	}

	/**
	 * Adds multiple temporal requirements that tick AFTER this list's cells.
	 *
	 * @param t the temporals to add as requirements
	 * @param <T> the temporal type
	 * @return this CellList for method chaining
	 */
	public <T extends Temporal> CellList addRequirements(T... t) {
		requirements.addAll(Arrays.asList(t));
		return this;
	}

	/**
	 * Tracks PackedCollections for lifecycle management.
	 *
	 * <p>Collections added here will be destroyed when {@link #destroy()} is called.
	 * Use this for temporary buffers or working memory that should be cleaned up
	 * with the CellList.</p>
	 *
	 * @param t the PackedCollections to track
	 * @param <T> unused type parameter for varargs compatibility
	 * @return this CellList for method chaining
	 */
	public <T extends Temporal> CellList addData(PackedCollection... t) {
		if (data == null) data = new ArrayList<>();
		data.addAll(Arrays.asList(t));
		return this;
	}

	/**
	 * Maps each cell in this list to a destination cell.
	 *
	 * <p>Creates a new CellList with this list as parent, containing cells
	 * created by the destination function for each index.</p>
	 *
	 * @param dest function that creates destination cells by index
	 * @return new CellList with mapped cells
	 */
	public CellList map(IntFunction<Cell<PackedCollection>> dest) {
		return map(this, dest);
	}

	/**
	 * Combines this CellList with another, creating a unified list.
	 *
	 * <p>The resulting list contains cells from both lists and can be
	 * used for parallel processing or mixing.</p>
	 *
	 * @param cells the CellList to combine with
	 * @return new CellList containing cells from both lists
	 */
	public CellList and(CellList cells) {
		return cells(this, cells);
	}

	/**
	 * Creates multiple parallel branches from this CellList.
	 *
	 * <p>Each branch processes the same input through different cell chains.</p>
	 *
	 * @param dest functions that create destination cells for each branch
	 * @return array of CellLists, one per branch
	 */
	public CellList[] branch(IntFunction<Cell<PackedCollection>>... dest) {
		return branch(this, dest);
	}

	public CellList poly(IntFunction<CollectionProducer> decision) {
		CellList l = poly(1, () -> null, decision,
				stream().map(c -> (Function<DefaultWaveCellData, CollectionTemporalCellAdapter>) data -> (CollectionTemporalCellAdapter) c).toArray(Function[]::new));
		// TODO  By dropping the parent, we may be losing necessary dependencies
		// TODO  However, if it is included, operations will be invoked multiple times
		// TODO  Since the new polymorphic cells delegate to the operations of the
		// TODO  original cells in this current CellList
		// l.setParent(this);
		return l;
	}

	public CellList gr(double duration, int segments, IntUnaryOperator choices) {
		return gr(this, duration, segments, choices);
	}

	public CellList grid(double duration, int segments, IntToDoubleFunction choices) {
		return grid(this, duration, segments, choices);
	}

	public CellList grid(double duration, int segments, IntFunction<Producer<PackedCollection>> choices) {
		return grid(this, duration, segments, choices);
	}

	public CellList f(IntFunction<Factor<PackedCollection>> filter) {
		return f(this, filter);
	}

	public CellList d(IntFunction<Producer<PackedCollection>> delay) { return d(this, delay); }

	public CellList d(IntFunction<Producer<PackedCollection>> delay,
					  IntFunction<Producer<PackedCollection>> scale) {
		return d(this, delay, scale);
	}

	public CellList m(IntFunction<Cell<PackedCollection>> adapter) {
		return m(this, adapter);
	}

	public CellList m(IntFunction<Cell<PackedCollection>> adapter, IntFunction<Gene<PackedCollection>> transmission) {
		return m(this, adapter, transmission);
	}

	public CellList m(List<Cell<PackedCollection>> adapter, List<Cell<PackedCollection>> destinations) {
		return m(this, adapter, destinations);
	}

	public CellList m(List<Cell<PackedCollection>> adapter, List<Cell<PackedCollection>> destinations, IntFunction<Gene<PackedCollection>> transmission) {
		return m(this, adapter, destinations, transmission);
	}

	public CellList mself(List<Cell<PackedCollection>> adapter, IntFunction<Gene<PackedCollection>> transmission) {
		return mself(this, adapter, transmission);
	}

	public CellList m(IntFunction<Cell<PackedCollection>> adapter, List<Cell<PackedCollection>> destinations) {
		return m(this, adapter, destinations);
	}

	public CellList m(IntFunction<Cell<PackedCollection>> adapter,
					  List<Cell<PackedCollection>> destinations,
					  IntFunction<Gene<PackedCollection>> transmission) {
		return m(this, adapter, destinations, transmission);
	}

	public CellList mself(IntFunction<Cell<PackedCollection>> adapter, IntFunction<Gene<PackedCollection>> transmission) {
		return mself(this, adapter, transmission);
	}

	public CellList mself(IntFunction<Cell<PackedCollection>> adapter,
						  IntFunction<Gene<PackedCollection>> transmission,
						  IntFunction<Cell<PackedCollection>> passthrough) {
		return mself(this, adapter, transmission, passthrough);
	}

	public CellList m(IntFunction<Cell<PackedCollection>> adapter,
					  IntFunction<Cell<PackedCollection>> destinations,
					  IntFunction<Gene<PackedCollection>> transmission) {
		return m(this, adapter, destinations, transmission);
	}

	/**
	 * Sums all cell outputs into a single output.
	 *
	 * <p>Creates a new CellList that combines all cell outputs into one signal.</p>
	 *
	 * @return new CellList with summed output
	 */
	public CellList sum() { return sum(this); }

	/**
	 * Creates a {@link BufferedOutputScheduler} for real-time audio output to shared memory.
	 *
	 * <p>This is the primary method for real-time audio streaming. The scheduler
	 * manages timing and buffer safety to prevent underruns and overruns.</p>
	 *
	 * <pre>{@code
	 * BufferedOutputScheduler scheduler = cells.buffer("/dev/shm/audio");
	 * scheduler.start();
	 * // ... audio plays in real-time ...
	 * scheduler.stop();
	 * }</pre>
	 *
	 * @param location shared memory location path
	 * @return scheduler configured for the shared memory location
	 */
	public BufferedOutputScheduler buffer(String location) {
		return buffer(new SharedMemoryAudioLine(location));
	}

	/**
	 * Creates a {@link BufferedOutputScheduler} for real-time audio output.
	 *
	 * @param line the audio line to use (implements InputLine and/or OutputLine)
	 * @return scheduler configured for the audio line
	 */
	public BufferedOutputScheduler buffer(BufferedAudio line) {
		return BufferedOutputScheduler.create(
				line instanceof InputLine ? (InputLine) line : null,
				line instanceof OutputLine ? (OutputLine) line : null,
				this);
	}

	/**
	 * Creates a {@link TemporalRunner} that buffers output to a destination collection.
	 *
	 * <p>Use this for offline processing where output is written to a
	 * PackedCollection rather than real-time playback.</p>
	 *
	 * @param destination producer for the destination collection
	 * @return TemporalRunner configured to write to the destination
	 */
	public TemporalRunner buffer(Producer<PackedCollection> destination) {
		return buffer(this, destination);
	}

	/**
	 * Converts this CellList to an {@link AudioLineOperation}.
	 *
	 * <p>The returned operation can be used with {@link BufferedOutputScheduler#create}
	 * for more control over the real-time processing pipeline.</p>
	 *
	 * @return AudioLineOperation that processes through this CellList
	 */
	public AudioLineOperation toLineOperation() {
		return (in, out, frames) -> buffer(out);
	}

	public Supplier<Runnable> export(PackedCollection destinations) {
		return export(this, destinations);
	}

	public CellList mixdown(double seconds) { return mixdown(this, seconds); }

	public CellList csv(IntFunction<File> f) {
		return csv(this, f);
	}

	public CellList o(IntFunction<File> f) {
		return o(this, f);
	}

	public CellList om(IntFunction<File> f) {
		return om(this, f);
	}

	/**
	 * Returns the parent CellLists.
	 *
	 * <p>Parents' temporals tick before this list's cells.</p>
	 *
	 * @return list of parent CellLists
	 */
	public List<CellList> getParents() { return parents; }

	/**
	 * Returns the temporal requirements.
	 *
	 * <p>Requirements tick after this list's cells.</p>
	 *
	 * @return the requirements TemporalList
	 */
	public TemporalList getRequirements() { return requirements; }

	/**
	 * Returns the cleanup callbacks to run during reset.
	 *
	 * @return list of final callbacks
	 */
	public List<Runnable> getFinals() { return finals; }

	/**
	 * Collects all cells from this list and all parents.
	 *
	 * @return collection of all cells in the hierarchy
	 */
	public Collection<Cell<PackedCollection>> getAll() {
		List<Cell<PackedCollection>> all = new ArrayList<>();
		parents.stream().map(CellList::getAll).flatMap(Collection::stream).forEach(c -> append(all, c));
		forEach(c -> append(all, c));

		return all;
	}

	/**
	 * Collects all temporals in tick order.
	 *
	 * <p><strong>This method determines tick execution order.</strong></p>
	 *
	 * <p>Collection order:</p>
	 * <ol>
	 *   <li><strong>Parents' temporals</strong> - Collected recursively, depth-first</li>
	 *   <li><strong>This list's cells</strong> - Cells that implement {@link Temporal}</li>
	 *   <li><strong>Requirements</strong> - Added via {@link #addRequirement(Temporal)}</li>
	 * </ol>
	 *
	 * <p><strong>Important:</strong> If you need something to tick before the current
	 * list's cells, add it to a parent CellList. Do NOT create custom pre-tick mechanisms.</p>
	 *
	 * @return TemporalList containing all temporals in tick order
	 */
	public TemporalList getAllTemporals() {
		TemporalList all = new TemporalList();
		parents.stream().map(CellList::getAllTemporals).flatMap(Collection::stream).forEach(c -> append(all, c));

		stream().map(c -> c instanceof Temporal ? (Temporal) c : null)
				.filter(Objects::nonNull).forEach(t -> append(all, t));

		requirements.forEach(c -> append(all, c));

		return all;
	}

	/**
	 * Collects all setup operations in execution order.
	 *
	 * <p>Collection order:</p>
	 * <ol>
	 *   <li>Parents' setups (recursively)</li>
	 *   <li>Cells implementing {@link Setup}</li>
	 *   <li>Requirements implementing {@link Setup}</li>
	 *   <li>Explicit setups from {@link #addSetup(Setup)}</li>
	 * </ol>
	 *
	 * @return list of all setup operations
	 */
	public List<Setup> getAllSetup() {
		List<Setup> all = new ArrayList<>();
		parents.stream().map(CellList::getAllSetup).flatMap(Collection::stream).forEach(c -> append(all, c));

		stream().map(c -> c instanceof Setup ? (Setup) c : null)
				.filter(Objects::nonNull).forEach(t -> append(all, t));

		requirements.stream().map(c -> c instanceof Setup ? (Setup) c : null)
				.filter(Objects::nonNull).forEach(c -> append(all, c));

		setups.stream().forEach(s -> append(all, s));

		return all;
	}

	/**
	 * Collects all root cells (entry points) from the hierarchy.
	 *
	 * <p>Roots receive initial push signals during {@link #tick()} to trigger data flow.</p>
	 *
	 * @return collection of all root receptors
	 */
	public Collection<Receptor<PackedCollection>> getAllRoots() {
		List<Receptor<PackedCollection>> all = new ArrayList<>();
		parents.stream().map(CellList::getAllRoots).flatMap(Collection::stream).forEach(c -> append(all, c));
		roots.forEach(c -> append(all, c));

		return all;
	}

	/**
	 * Creates the setup operation for initializing all cells.
	 *
	 * <p>The setup operation should be executed once before any ticks.</p>
	 *
	 * @return supplier providing the setup runnable
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("CellList Setup");
		getAllSetup().stream().map(Setup::setup).forEach(setup::add);
		return setup;
	}

	/**
	 * Creates the tick operation for one processing cycle.
	 *
	 * <p>The tick operation:</p>
	 * <ol>
	 *   <li>Pushes to all roots (with {@code c(0.0)}) to trigger data flow</li>
	 *   <li>Ticks all temporals in order from {@link #getAllTemporals()}</li>
	 * </ol>
	 *
	 * <p>The order of temporals is critical - see {@link #getAllTemporals()} for details.</p>
	 *
	 * @return supplier providing the tick runnable
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("CellList Tick");
		getAllRoots().stream().map(r -> r.push(c(0.0))).forEach(tick::add);
		tick.add(getAllTemporals().tick());
		return tick;
	}

	/**
	 * Resets all cells to their initial state.
	 *
	 * <p>Reset order:</p>
	 * <ol>
	 *   <li>Run finals callbacks</li>
	 *   <li>Reset parents</li>
	 *   <li>Reset all cells in this list</li>
	 *   <li>Reset requirements</li>
	 * </ol>
	 */
	@Override
	public void reset() {
		finals.forEach(Runnable::run);
		parents.forEach(CellList::reset);
		forEach(Cell::reset);
		requirements.reset();
	}

	/**
	 * Destroys this CellList and releases all resources.
	 *
	 * <p>Destroy order:</p>
	 * <ol>
	 *   <li>Destroy tracked data collections</li>
	 *   <li>Destroy parents</li>
	 *   <li>Destroy cells implementing {@link Destroyable}</li>
	 * </ol>
	 *
	 * <p>After calling destroy, this CellList should not be used.</p>
	 */
	@Override
	public void destroy() {
		Destroyable.super.destroy();

		if (data != null) {
			data.forEach(PackedCollection::destroy);
			data.clear();
		}

		parents.forEach(CellList::destroy);
		forEach(c -> {
			if (c instanceof Destroyable) ((Destroyable) c).destroy();
		});
	}

	/**
	 * Returns a collector that accumulates cells into a CellList.
	 *
	 * <p>Useful for stream operations:</p>
	 * <pre>{@code
	 * CellList cells = cellStream.collect(CellList.collector());
	 * }</pre>
	 *
	 * @return stream collector for CellList
	 */
	public static Collector<Cell<PackedCollection>, ?, CellList> collector() {
		return Collectors.toCollection(CellList::new);
	}
}
