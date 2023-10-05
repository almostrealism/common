/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.Memory;
import io.almostrealism.code.NameProvider;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.collect.CollectionScopeInputManager;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelSupport;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ProviderAwareArgumentMap;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class MemoryDataArgumentMap<S, A> extends ProviderAwareArgumentMap<S, A> {
	public static final boolean enableDestinationDetection = true;
	public static final boolean enableGlobalArgumentMap = false;

	public static boolean enableArgumentAggregation = true;

	private static ContextSpecific<MemoryDataArgumentMap> globalMaps;
	private static ContextSpecific<MemoryDataArgumentMap> globalMapsKernel;

	private final Map<Memory, ArrayVariable<A>> mems;
	private final Map<MemoryDataRef, Integer> aggregatePositions;
	private final List<RootDelegateProviderSupplier> rootDelegateSuppliers;
	private final boolean kernel;

	private OperationList prepareData;
	private OperationList postprocessData;

	private IntFunction<MemoryData> aggregateGenerator;
	private int aggregateLength;

	private MemoryData aggregateData;
	private Producer<MemoryData> aggregateSupplier;
	private ArrayVariable<A> aggregateArgument;

	public MemoryDataArgumentMap() { this(null); }

	public MemoryDataArgumentMap(IntFunction<MemoryData> aggregateGenerator) { this(aggregateGenerator, true); }

	public MemoryDataArgumentMap(IntFunction<MemoryData> aggregateGenerator, boolean kernel) {
		this.mems = new HashMap<>();
		this.aggregatePositions = new HashMap<>();
		this.rootDelegateSuppliers = new ArrayList<>();
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

			md = (MemoryData) ((Provider) provider).get();

			// If the provider points to a MemoryData that is stored outside of device memory,
			// it is a candidate for argument aggregation below
			generateArg = md.getMem().getProvider() != Hardware.getLocalHardware().getDataContext().getKernelMemoryProvider();
		}

		if (md == null) return null;
		if (md.getMem() == null) {
			throw new IllegalArgumentException();
		}

		if (mems.containsKey(md.getMem())) {
			// If the root delegate already had an argument produced for it,
			// return that
			return delegateProvider.getArgument(p, key, mems.get(md.getMem()), md.getOffset());
		} else {
			ArrayVariable var;

			if (generateArg) {
				// If aggregation is desired for this MemoryData, try to
				// generate the aggregate argument for the root delegate
				var = generateArgument(p, new RootDelegateProviderSupplier(md), md.getRootDelegate());
			} else {
				// Otherwise, just obtain the array variable for the root delegate
				var = delegateProvider.getArgument(p, new RootDelegateProviderSupplier(md), null, -1);
			}

			// Record that this MemoryData has var as its root delegate
			mems.put(md.getMem(), var);

			// Return an ArrayVariable that delegates to the correct position of the root delegate
			return delegateProvider.getArgument(p, key, var, md.getOffset());
		}
	}

	@Override
	public void destroy() {
		super.destroy();
		rootDelegateSuppliers.forEach(RootDelegateProviderSupplier::destroy);
		mems.forEach((k, v) -> v.destroy());
		mems.clear();
		aggregatePositions.clear();
		prepareData.destroy();
		postprocessData.destroy();
		if (aggregateData != null) aggregateData.destroy();
	}

	protected MemoryData rootDelegate(MemoryData mw) {
		if (mw.getDelegate() == null) {
			return mw;
		} else {
			return rootDelegate(mw.getDelegate());
		}
	}

	protected class RootDelegateProviderSupplier implements Supplier<Evaluable<? extends MemoryData>>, Delegated<Provider>, KernelSupport {
		private Provider provider;

		public RootDelegateProviderSupplier(MemoryData mem) {
			this.provider = new Provider<>(rootDelegate(mem));
			rootDelegateSuppliers.add(this);
		}

		@Override
		public boolean isKernelEnabled() {
			// TODO  This is not ideal, but until we cleanup kernel functionality further
			// TODO  allowing this to be treated as KernelSupport is not compatible with
			// TODO  the scenarios where we need to disable kernel operations
			return Hardware.enableKernelOps;
		}

		@Override
		public Evaluable<? extends MemoryData> get() { return provider; }

		@Override
		public Provider getDelegate() { return provider; }

		public void destroy() { this.provider = null; }
	}

	protected MemoryData getAggregateData() {
		if (aggregateLength > 0 && aggregateData == null) {
			aggregateData = aggregateGenerator.apply(aggregateLength);
		}

		return aggregateData;
	}

	protected Producer<MemoryData> getAggregateSupplier() {
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
		if (md.getMem().getProvider() == Hardware.getLocalHardware().getDataContext().getKernelMemoryProvider()) return null;

		if (aggregatePositions.containsKey(new MemoryDataRef(md))) {
			// If aggregation has already occurred for this MemoryData,
			// then return an argument that delegates to the correct position
			// of the aggregated argument
			return delegateProvider.getArgument(p, key, getAggregateArgument(p), aggregatePositions.get(new MemoryDataRef(md)));
		}

		if (aggregateData != null) {
			throw new IllegalArgumentException("Cannot generate argument when aggregate data is already built");
		}

		int pos = aggregateLength;

		// Remember the position of this MemoryData in the aggregate argument
		// in case another attempt is made at aggregation for a different
		// provider of the same underlying MemoryData
		aggregatePositions.put(new MemoryDataRef(md), pos);

		// Update the pre/post operations to move the data into and out of the aggregate argument data
		prepareData.add(new MemoryDataCopy("Agg Prep", () -> md, this::getAggregateData, 0, pos, md.getMemLength()));
		postprocessData.add(new MemoryDataCopy("Agg Post", this::getAggregateData, () -> md, pos, 0, md.getMemLength()));

		// Expand the required length of the aggregate argument
		aggregateLength += md.getMemLength();

		// Return a delegate to the aggregate argument
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
			MemoryDataArgumentMap map = new MemoryDataArgumentMap(aggregateGenerator, kernel);
			map.setDelegateProvider(CollectionScopeInputManager.getInstance());
			return map;
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

	private static class MemoryDataRef {
		private MemoryData md;

		public MemoryDataRef(MemoryData md) {
			this.md = md;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof MemoryDataRef)) return false;
			MemoryDataRef that = (MemoryDataRef) o;
			return md == that.md;
		}

		@Override
		public int hashCode() {
			return Objects.hash(md);
		}
	}
}
