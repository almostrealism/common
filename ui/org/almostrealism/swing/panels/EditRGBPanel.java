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

import java.awt.Color;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.almostrealism.color.RGB;
import org.almostrealism.texture.GraphicsConverter;
import org.almostrealism.util.Defaults;

/** An EditRGBPanel object can be used to specify an RGB color. */
public class EditRGBPanel extends JPanel {
  private JFormattedTextField redField, greenField, blueField;
  private JButton selectColorButton;

	/**
	  Constructs a new EditRGBPanel object with the initial values set to 0.0 (black).
	*/
	
	public EditRGBPanel() {
		this(new RGB(0.0, 0.0, 0.0));
	}
	
	/**
	  Constructs a new EditRGBPanel object with the initial values set to those of the specified RGB object.
	*/
	
	public EditRGBPanel(RGB color) {
		super(new GridLayout(0, 2));
		
		this.redField = new JFormattedTextField(Defaults.decimalFormat);
		this.greenField = new JFormattedTextField(Defaults.decimalFormat);
		this.blueField = new JFormattedTextField(Defaults.decimalFormat);
		
		this.redField.setColumns(6);
		this.greenField.setColumns(6);
		this.blueField.setColumns(6);
		
		FocusListener listener = new FocusAdapter() {
			public void focusGained(FocusEvent event) {
				JTextField field = (JTextField)event.getSource();
				field.setSelectionStart(0);
				field.setSelectionEnd(field.getText().length());
			}
		};
		
		this.redField.addFocusListener(listener);
		this.greenField.addFocusListener(listener);
		this.blueField.addFocusListener(listener);
		
		this.setSelectedColor(color);
		
		this.selectColorButton = new JButton("Select...");
		
		this.selectColorButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				double r = ((Double)redField.getValue()).doubleValue();
				double g = ((Double)greenField.getValue()).doubleValue();
				double b = ((Double)blueField.getValue()).doubleValue();
				
				Color currentColor = GraphicsConverter.convertToAWTColor(new RGB(r, g, b));
				
				java.awt.Color newColor = JColorChooser.showDialog(null, "Select Color", currentColor);
				
				if (newColor != null) {
					RGB newRGB = GraphicsConverter.convertToRGB(newColor);
					setSelectedColor(newRGB);
				} else {
					return;
				}
			}
		});
		
		this.add(new JLabel("Red: "));
		this.add(this.redField);
		this.add(new JLabel("Green: "));
		this.add(this.greenField);
		this.add(new JLabel("Blue: "));
		this.add(this.blueField);
		this.add(this.selectColorButton);
	}
	
	/**
	  Updates the fields of this EditRGBPanel object to display the values for the color represented by the specified RGB object.
	*/
	
	public void setSelectedColor(RGB color) {
		this.redField.setValue(new Double(color.getRed()));
		this.greenField.setValue(new Double(color.getGreen()));
		this.blueField.setValue(new Double(color.getBlue()));
	}
	
	/**
	  Returns the color selected by this EditRGBPanel object as an RGB object.
	*/
	
	public RGB getSelectedColor() {
		double r = ((Number)this.redField.getValue()).doubleValue();
		double g = ((Number)this.greenField.getValue()).doubleValue();
		double b = ((Number)this.blueField.getValue()).doubleValue();
		
		RGB color = new RGB(r, g, b);
		
		return color;
	}
}
