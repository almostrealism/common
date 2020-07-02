/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.graph;

public abstract class CellAdapter<T> implements Cell<T> {
	private ProteinCache<T> o;
	private Receptor<T> r;
	private Receptor<T> meter;
	
	private String name;
	
	public void setName(String n) { this.name = n; }
	
	public String getName() { return this.name; }

	@Override
	public void setReceptor(Receptor<T> r) { this.r = r; }
	
	public Receptor<T> getReceptor() { return this.r; }

	@Override
	public void setProteinCache(ProteinCache<T> p) { this.o = p; }
	
	public long addProtein(T p) { return o.addProtein(p); }
	
	public T getProtein(long index) { return o.getProtein(index); }
	
	public void setMeter(Receptor<T> m) { this.meter = m; }

	protected void pushToMeter(long proteinIndex) { this.meter.push(proteinIndex); }
	
	/** Push to the {@link Receptor}. */
	@Override
	public void push(long proteinIndex) {
		if (meter != null) meter.push(proteinIndex);
		if (r != null) r.push(proteinIndex);
	}

	@Override
	public String toString() {
		String className = getClass().getSimpleName();
		return name == null ? className : (name + " (" + className + ")");
	}
}
