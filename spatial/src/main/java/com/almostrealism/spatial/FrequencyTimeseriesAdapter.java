/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * An abstract adapter class for {@link FrequencyTimeseries} that delegates
 * to another frequency timeseries.
 *
 * <p>{@code FrequencyTimeseriesAdapter} implements the Adapter/Proxy pattern,
 * allowing subclasses to wrap and transform frequency data from different
 * sources. It delegates all abstract method calls to a delegate timeseries
 * obtained via {@link #getDelegate(int)}.</p>
 *
 * <h2>Implementation Pattern</h2>
 * <p>Subclasses should override {@link #getDelegate(int)} to return the
 * appropriate delegate timeseries for each layer:</p>
 * <pre>{@code
 * public class MyAdapter extends FrequencyTimeseriesAdapter {
 *     private FrequencyTimeseries source;
 *
 *     @Override
 *     protected FrequencyTimeseries getDelegate(int layer) {
 *         return source;
 *     }
 * }
 * }</pre>
 *
 * @see FrequencyTimeseries
 * @see SpatialWaveDetails
 * @see GenomicTimeseries
 */
public abstract class FrequencyTimeseriesAdapter extends FrequencyTimeseries {

	/**
	 * Returns the delegate timeseries for the specified layer.
	 *
	 * <p>The default implementation throws {@link UnsupportedOperationException}.
	 * Subclasses should override this method to return the actual delegate.</p>
	 *
	 * @param layer the layer index
	 * @return the delegate frequency timeseries
	 * @throws UnsupportedOperationException if not overridden
	 */
	protected FrequencyTimeseries getDelegate(int layer) {
		throw new UnsupportedOperationException();
	}

	/**
	 * {@inheritDoc}
	 * <p>Delegates to {@link #getDelegate(int)}.</p>
	 */
	@Override
	public int getIndex(int layer) {
		return getDelegate(layer).getIndex(layer);
	}

	/**
	 * {@inheritDoc}
	 * <p>Delegates to {@link #getDelegate(int)}.</p>
	 */
	@Override
	public double getFrequencyTimeScale(int layer) {
		return getDelegate(layer).getFrequencyTimeScale(layer);
	}

	/**
	 * {@inheritDoc}
	 * <p>Delegates to {@link #getDelegate(int)}.</p>
	 */
	@Override
	public double getElementInterval(int layer) {
		return getDelegate(layer).getElementInterval(layer);
	}

	/**
	 * {@inheritDoc}
	 * <p>Delegates to {@link #getDelegate(int)}, returning {@code null}
	 * if the delegate is null.</p>
	 */
	@Override
	public List<PackedCollection> getSeries(int layer) {
		FrequencyTimeseries delegate = getDelegate(layer);
		if (delegate == null) return null;

		return delegate.getSeries(layer);
	}

	/**
	 * {@inheritDoc}
	 * <p>Returns {@code false} by default. Subclasses may override.</p>
	 */
	@JsonIgnore
	@Override
	public boolean isEmpty() { return false; }
}
