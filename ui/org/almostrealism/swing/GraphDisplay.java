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

package org.almostrealism.swing;

import java.awt.Graphics;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.swing.JPanel;

/**
 * @author Mike Murray
 */
public class GraphDisplay extends JPanel {
	private int scale = 4;
	
	private int maxEntries;
	private List entries = new ArrayList();

	public GraphDisplay() { this(1000); }
	public GraphDisplay(int maxEntries) { this.maxEntries = maxEntries; }
	
	public void addEntry(int i) {
		this.entries.add(Integer.valueOf(i));
		if (this.entries.size() > this.maxEntries) this.entries.remove(0);
	}
	
	public void paint(Graphics g) {
		int i = 0;
		Iterator itr = this.entries.iterator();
		
		while (itr.hasNext()) {
			g.drawLine(this.scale * i, 0, this.scale * i, 
					this.scale * ((Integer)itr.next()).intValue());
			i++;
		}
	}
}
