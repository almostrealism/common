package com.almostrealism.spatial.series;

import com.almostrealism.spatial.FrequencyTimeseriesAdapter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A base class for tree-structured timeseries that delegates frequency data
 * to a {@link SoundDataTimeseries}.
 *
 * <p>{@code SimpleTimeseries} combines the {@link FrequencyTimeseriesAdapter}
 * pattern with the {@link Tree} interface to enable hierarchical organization
 * of audio recordings or model outputs. Each node has:</p>
 * <ul>
 *   <li>A unique key identifier</li>
 *   <li>An optional delegate for frequency visualization</li>
 *   <li>A list of child nodes forming a tree structure</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * SimpleTimeseries<RecordedTimeseries> root = new SimpleTimeseries<>("root");
 * RecordedTimeseries child1 = new RecordedTimeseries("track1");
 * RecordedTimeseries child2 = new RecordedTimeseries("track2");
 * root.getChildren().add(child1);
 * root.getChildren().add(child2);
 * }</pre>
 *
 * @param <T> the type of child tree nodes
 * @see FrequencyTimeseriesAdapter
 * @see Tree
 * @see RecordedTimeseries
 * @see AudioModelOutput
 */
public class SimpleTimeseries<T extends Tree> extends FrequencyTimeseriesAdapter implements Tree<T> {
	private String key;

	private SoundDataTimeseries delegate;
	private List<T> children;

	/**
	 * Creates an empty simple timeseries.
	 */
	public SimpleTimeseries() { }

	/**
	 * Creates a simple timeseries with the specified key.
	 *
	 * @param key the unique identifier
	 */
	public SimpleTimeseries(String key) {
		this.key = key;
	}

	/**
	 * Sets the unique identifier for this timeseries.
	 *
	 * @param key the key
	 */
	public void setKey(String key) { this.key = key; }

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getKey() { return key; }

	/**
	 * Sets the delegate timeseries for frequency visualization.
	 *
	 * @param delegate the sound data timeseries delegate
	 */
	@JsonIgnore
	public void setDelegate(SoundDataTimeseries delegate) {
		this.delegate = delegate;
	}

	/**
	 * Returns the delegate timeseries for frequency visualization.
	 *
	 * @return the delegate, or {@code null} if not set
	 */
	@JsonIgnore
	public SoundDataTimeseries getDelegate() { return delegate; }

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected SoundDataTimeseries getDelegate(int layer) {
		return getDelegate();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Always returns 1.</p>
	 */
	@JsonIgnore
	@Override
	public int getLayerCount() { return 1; }

	/**
	 * Returns the collection of child nodes in the tree.
	 *
	 * <p>Creates an empty list if children haven't been initialized.</p>
	 *
	 * @return the mutable collection of children
	 */
	@Override
	public Collection<T> getChildren() {
		if (children == null) {
			children = new ArrayList<>();
		}

		return children;
	}
}
