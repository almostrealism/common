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

package org.almostrealism.swing.panels;

import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.text.ParseException;

import javax.swing.InputVerifier;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JFormattedTextField.AbstractFormatter;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import io.almostrealism.util.NumberFormats;

/**
 * A PercentagePanel object can be used to display a decimal value
 * (0.0 - 1.0) as a percentage and provide a slider for setting the value.
 * 
 * @author Mike Murray
 */
public class PercentagePanel extends JPanel {
  private double value;
  
  private JFormattedTextField valueField;
  private JSlider slider;

	/**
	 * Constructs a new PercentagePanel object using a grid layout.
	 */
	public PercentagePanel() { this(new GridLayout(0, 1)); }
	
	/**
	 * Constructs a new PercentagePanel object using the specified layout.
	 */
	public PercentagePanel(LayoutManager layout) {
		super(layout);
		
		this.valueField = new JFormattedTextField(NumberFormats.decimalFormat);
		this.valueField.setColumns(6);
		this.slider = new JSlider(JSlider.HORIZONTAL, 0, 100, 0);
		
		this.valueField.setInputVerifier(new InputVerifier() {
			public boolean verify(JComponent source) {
				JFormattedTextField field = (JFormattedTextField)source;
				AbstractFormatter formatter = field.getFormatter();
				
				double v = 0.0;
				
				if (formatter != null) {
					String text = field.getText();
					
					try {
						v = ((Number)formatter.stringToValue(text)).doubleValue();
						field.commitEdit();
					} catch (ParseException pe) {
						return false;
					}
				}
				
				PercentagePanel.this.setValue(v);
				
				return true;
			}
		});
		
		this.slider.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent event) {
				PercentagePanel.this.setValue(PercentagePanel.this.slider.getValue() / 100.0);
			}
		});
		
		this.setValue(0.0);
		
		super.add(this.slider);
		super.add(this.valueField);
	}
	
	/**
	 * Adds a change listener to the slider on this PercentagePanel.
	 * 
	 * @param l  ChangeListener to add.
	 */
	public void addChangeListener(ChangeListener l) {
		this.slider.addChangeListener(l);
	}
	
	public void setSliderName(String name) {
		this.slider.setName(name);
	}
	
	/**
	 * Sets the value (0.0 - 1.0) displayed by this PercentagePanel object.
	 * 
	 * @param value  The value to use.
	 */
	public void setValue(double value) {
		this.value = value;
		
		this.valueField.setValue(Double.valueOf(this.value));
		this.slider.setValue((int)(this.value * 100));
	}
	
	/**
	 * @return  The value displayed by this PercentagePanel object.
	 */
	public double getValue() { return this.value; }
}
