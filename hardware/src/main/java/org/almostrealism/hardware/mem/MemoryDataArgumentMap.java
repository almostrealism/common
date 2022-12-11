/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.Computation;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.Memory;
import io.almostrealism.code.NameProvider;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.Shape;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ProviderAwareArgumentMap;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class MemoryDataArgumentMap<S, A> extends ProviderAwareArgumentMap<S, A> {
	public static final boolean enableDestinationDetection = true;
	public static final boolean enableGlobalArgumentMap = false;

	public static final boolean enableArgumentAggregation = true;
	public static final boolean enableEvaluableAggregation = false;

	private static ContextSpecific<MemoryDataArgumentMap> globalMaps;
	private static ContextSpecific<MemoryDataArgumentMap> globalMapsKernel;

	private final Map<Memory, ArrayVariable<A>> mems;
	private final boolean kernel;

	private OperationList prepareData;
	private OperationList postprocessData;

	private IntFunction<MemoryData> aggregateGenerator;
	private int aggregateLength;

	private MemoryData aggregateData;
	private Supplier<? extends Evaluable<MemoryData>> aggregateSupplier;
	private ArrayVariable<A> aggregateArgument;

	public MemoryDataArgumentMap() { this(null); }

	public MemoryDataArgumentMap(IntFunction<MemoryData> aggregateGenerator) { this(aggregateGenerator, true); }

	public MemoryDataArgumentMap(IntFunction<MemoryData> aggregateGenerator, boolean kernel) {
		this.mems = new HashMap<>();
		this.kernel = kernel;

		this.aggregateGenerator = aggregateGenerator;

		if (enableArgumentAggregation) {
			prepareData = new OperationList();
			postprocessData = new OperationList();
		}
	}

	public OperationList getPrepareData() { return prepareData; }
	public OperationList getPostprocessData() { return postprocessData; }

	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		ArrayVariable<A> arg = super.get(key, p);
		if (arg != null) return arg;

		MemoryData md;

		boolean generateArg = false;

		// A MemoryDataDestination carries information about how to produce
		// the data in that destination, along with the MemoryData itself.
		// There needs to be a way to return a delegated variable, as we do
		// below, but not lose the knowledge that we rely on during the
		// creation of required scopes, ie the knowledge of how that data
		// is populated. The root delegate will have no logical way to do
		// this because it may have many children produced in different
		// ways, so it probably has to be stored with the Delegated variable,
		// but it cannot be tracked using the delegate field because that
		// is already used to point at the root delegate MemoryData
		if (enableDestinationDetection && !kernel && key instanceof MemoryDataDestination) {
			Object dest = ((MemoryDataDestination) key).get().evaluate();
			if (dest != null && !(dest instanceof MemoryData)) {
				throw new RuntimeException();
			}

			md = (MemoryData) dest;
		} else {
			Object provider = key.get();
			if (!(provider instanceof Provider)) return null;
			if (!(((Provider) provider).get() instanceof MemoryData)) return null;

			generateArg = true;
			md = (MemoryData) ((Provider) provider).get();
		}

		if (md == null) return null;

		ArrayVariable<A> generatedArg = null;
		if (generateArg) generatedArg = generateArgument(p, key, md);
		if (generatedArg != null) return generatedArg;

		if (generatedArg != null) {
			return generatedArg;
		} if (mems.containsKey(md.getMem())) {
			return delegateProvider.getArgument(p, key, mems.get(md.getMem()), md.getOffset());
		} else {
			// Obtain the array variable for the root delegate of the MemoryData
			ArrayVariable var = delegateProvider.getArgument(p, new RootDelegateProviderSupplier(md), null, -1);

			// Record that this MemoryData has var as its root delegate
			mems.put(md.getMem(), var);

			// Return an ArrayVariable that delegates to the correct position of the root delegate
			return delegateProvider.getArgument(p, key, var, md.getOffset());
		}
	}

	protected MemoryData rootDelegate(MemoryData mw) {
		if (mw.getDelegate() == null) {
			return mw;
		} else {
			return rootDelegate(mw.getDelegate());
		}
	}

	protected class RootDelegateProviderSupplier implements Supplier<Evaluable<? extends MemoryData>>, Delegated<Provider> {
		private final Provider provider;

		public RootDelegateProviderSupplier(MemoryData mem) {
			this.provider = new Provider<>(rootDelegate(mem));
		}

		@Override
		public Evaluable<? extends MemoryData> get() { return provider; }

		@Override
		public Provider getDelegate() { return provider; }
	}

	protected MemoryData getAggregateData() {
		if (aggregateLength > 0 && aggregateData == null) {
			aggregateData = aggregateGenerator.apply(aggregateLength);
		}

		return aggregateData;
	}

	protected Supplier<? extends Evaluable<MemoryData>> getAggregateSupplier() {
		if (aggregateSupplier == null) {
			aggregateSupplier = () -> new Provider<>(getAggregateData());
		}

		return aggregateSupplier;
	}

	protected ArrayVariable<A> getAggregateArgument(NameProvider p) {
		if (aggregateArgument == null) {
			aggregateArgument = delegateProvider.getArgument(p, (Supplier) getAggregateSupplier(), null, -1);
		}

		return aggregateArgument;
	}

	private ArrayVariable<A> generateArgument(NameProvider p, Supplier key, MemoryData md) {
		if (!enableArgumentAggregation || aggregateGenerator == null) return null;
		return generateArgument(p, key, () -> md, md.getMemLength());
	}

	private ArrayVariable<A> generateArgument(NameProvider p, Supplier key, Supplier<MemoryData> value, int size) {
		if (aggregateData != null) {
			throw new IllegalArgumentException("Cannot generate argument when aggregate data is already built");
		}

		int pos = aggregateLength;

		prepareData.add(() -> () -> {
			MemoryData md = value.get();
			if (md == null) return;

			getAggregateData().setMem(pos, value.get().toArray(0, size), 0, size);
		});

		postprocessData.add(() -> () -> {
			MemoryData md = value.get();
			if (md == null) return;

			md.setMem(0, getAggregateData().toArray(pos, size), 0, size);
		});

		aggregateLength += size;
		return delegateProvider.getArgument(p, key, getAggregateArgument(p), pos);
	}

	@Override
	public void confirmArguments() {
		super.confirmArguments();

//		TODO  It seems like we should be able to do this here, but it seems to happen prior to arguments being properly
//		TODO  mapped. Unfortunately, it appears that until the prepareScope process is over, we don't truly know what
//		TODO  the arguments are...
//		if (aggregateData != null) {
//			System.out.println("WARN: Argument confirmation appears to be invoked more than once");
//		}
//
//		if (aggregateLength > 0) {
//			aggregateData = aggregateGenerator.apply(aggregateLength);
//		}
	}

	public static MemoryDataArgumentMap create(boolean kernel) { return create(null, kernel); }

	public static MemoryDataArgumentMap create(IntFunction<MemoryData> aggregateGenerator, boolean kernel) {
		if (!enableGlobalArgumentMap) {
			return new MemoryDataArgumentMap(aggregateGenerator, kernel);
		}

		return kernel ? getGlobalMapsKernel().getValue() : getGlobalMaps().getValue();
	}

	protected synchronized static ContextSpecific<MemoryDataArgumentMap> getGlobalMaps() {
		if (globalMaps == null) {
			globalMaps = new DefaultContextSpecific<>(() -> new MemoryDataArgumentMap(null, false), MemoryDataArgumentMap::destroy);
			globalMaps.init();
		}

		return globalMaps;
	}

	protected synchronized static ContextSpecific<MemoryDataArgumentMap> getGlobalMapsKernel() {
		if (globalMapsKernel == null) {
			globalMapsKernel = new DefaultContextSpecific<>(MemoryDataArgumentMap::new, MemoryDataArgumentMap::destroy);
			globalMapsKernel.init();
		}

		return globalMapsKernel;
	}
}
