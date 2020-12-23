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

package org.almostrealism.swing.displays;

import java.awt.Font;
import java.lang.reflect.InvocationTargetException;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * @author Mike Murray
 */
public class ScrollingTextDisplay extends JLabel implements Runnable {
	public static interface TextProducer { public String nextPhrase(); }
	
	private TextProducer producer;
	private String text, display;
	private int col;
	private int sleep;
	
	public ScrollingTextDisplay(TextProducer p, int col) {
		this.producer = p;
		this.col = col;
		
		super.setFont(new Font("Monospaced", Font.BOLD, 12));
		
		this.setSleep(160);
		
		this.text = p.nextPhrase();
		
		Thread t = new Thread(this);
		t.setDaemon(true);
		t.start();
	}
	
	public void run() {
		while (true) {
			try {
				Thread.sleep(this.sleep);
			} catch (InterruptedException ie) { }
			
			if (this.text.length() > 0) this.text = this.text.substring(1);
			
			if (this.text.length() > this.col) {
				this.display = this.text.substring(0, this.col);
			} else {
				StringBuffer s = new StringBuffer();
				for (int i = 0; i < this.col / 2; i++) s.append(" ");
				s.append(this.producer.nextPhrase());
				
				this.text = this.text.concat(s.toString());
				this.display = this.text;
			}
			
			try {
				SwingUtilities.invokeAndWait(new Runnable() {
					public void run() {
						ScrollingTextDisplay.this.setText(ScrollingTextDisplay.this.display);
					}
				});
			} catch (InterruptedException e) {
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * @return  The sleep time between each text shift in msecs.
	 */
	public int getSleep() { return sleep; }
	
	/**
	 * Sets the sleep time between each text shift.
	 * 
	 * @param sleep  The sleep time in msecs.
	 */
	public void setSleep(int sleep) { this.sleep = sleep; }
}
