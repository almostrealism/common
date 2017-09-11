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

import java.awt.Component;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;

import org.almostrealism.color.RGB;
import org.almostrealism.texture.GraphicsConverter;

/**
 * An ExtendedCellRenderer object can be used to render values in a table
 * including instances of RGB and Class.
 */
public class ExtendedCellRenderer extends DefaultTableCellRenderer {
  private Border selectedBorder, unselectedBorder;

	/**
	 * Constructs a new ExtendedCellRenderer object.
	 */
	public ExtendedCellRenderer() {}
	
	public Component getTableCellRendererComponent(JTable table, Object value,
							boolean isSelected, boolean hasFocus,
							int row, int column) {
		if (value instanceof RGB) {
			JLabel label = new JLabel();
			label.setOpaque(true);
			label.setBackground(GraphicsConverter.convertToAWTColor((RGB)value));
			label.setToolTipText(value.toString());
			
			if (isSelected == true) {
				this.selectedBorder = BorderFactory.createMatteBorder(2, 5, 2, 5, table.getSelectionBackground());
				label.setBorder(this.selectedBorder);
			} else {
				this.unselectedBorder = BorderFactory.createMatteBorder(2, 5, 2, 5, table.getBackground());
				label.setBorder(this.unselectedBorder);
			}
			
			return label;
		} else if (value instanceof Class) {
			String name = ((Class)value).getName();
			name = name.substring(name.lastIndexOf('.') + 1);
			
			if (name.equals("Editable$Selection")) name = "Selection";
			
			JTextArea label = new JTextArea();
			label.setEditable(false);
			label.setLineWrap(true);
			label.setWrapStyleWord(true);
			label.setText(name);
			
			if (isSelected == true) {
				this.selectedBorder = BorderFactory.createMatteBorder(2, 5, 2, 5, table.getSelectionBackground());
				label.setBorder(this.selectedBorder);
			} else {
				this.unselectedBorder = BorderFactory.createMatteBorder(2, 5, 2, 5, table.getBackground());
				label.setBorder(this.unselectedBorder);
			}
			
			return label;
		} else if (value instanceof String) {
			JTextArea textArea = new JTextArea();
			textArea.setEditable(false);
			textArea.setLineWrap(true);
			textArea.setWrapStyleWord(true);
			textArea.setText((String)value);
			
			if (isSelected == true) {
				textArea.setBackground(table.getSelectionBackground());
			} else {
				textArea.setBackground(table.getBackground());
			}
			
			return textArea;
		} else {
			return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
		}
	}
}
