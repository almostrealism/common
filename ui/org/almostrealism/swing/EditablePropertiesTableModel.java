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

import javax.swing.table.AbstractTableModel;

import org.almostrealism.util.Editable;

/**
  An EditablePropertiesTableModel object can be used to manage the data
  needed when displaying the properties of an Editable object in a table.
  The table model also handles applying changes made in the table to the
  stored Editable object.
*/
public class EditablePropertiesTableModel extends AbstractTableModel {
  public static final String columnNames[] = {"Property", "Description", "Type", "Value"};
  
  private Editable editing;

	/**
	  Constructs a new EditablePropertiesTableModel object.
	*/
	
	public EditablePropertiesTableModel() { }
	
	/**
	  Constructs a new EditablePropertiesTableModel object using the specified Editable object.
	*/
	
	public EditablePropertiesTableModel(Editable editing) {
		this.setEditing(editing);
	}
	
	/**
	  Sets the Editable object used by this EditablePropertiesTableModel object to the specified
	  Editable object and notifies listeners that all table data may have changed.
	*/
	
	public void setEditing(Editable editing) {
		this.editing = editing;
		
		super.fireTableDataChanged();
	}
	
	/**
	  Returns the Editable object used by this EditablePropertiesTableModel object.
	*/
	
	public Editable getEditing() {
		return this.editing;
	}
	
	/**
	  Returns the name of the column at the specified index.
	*/
	
	public String getColumnName(int index) {
		return EditablePropertiesTableModel.columnNames[index];
	}
	
	/**
	  Returns true if the cell at the specified row and column is editable.
	*/
	
	public boolean isCellEditable(int row, int column) {
		if (column == 3)
			return true;
		else
			return false;
	}
	
	/**
	  Returns the number of rows (editable properties displayed) in this table model.
	*/
	
	public int getRowCount() {
		if (this.editing == null)
			return 0;
		else
			return this.editing.getPropertyTypes().length;
	}
	
	/**
	  Returns the number of columns (4) of this table model.
	*/
	
	public int getColumnCount() {
		return EditablePropertiesTableModel.columnNames.length;
	}
	
	/**
	  Returns the value for the cell at the specified row and column index.
	*/
	
	public Object getValueAt(int row, int column) {
		if (column == 0) {
			return this.editing.getPropertyNames()[row];
		} else if (column == 1) {
			return this.editing.getPropertyDescriptions()[row];
		} else if (column == 2) {
			return this.editing.getPropertyTypes()[row];
		} else if (column == 3) {
			return this.editing.getPropertyValues()[row];
		} else {
			return null;
		}
	}
	
	/**
	  Sets the value of the Editable object property that is represented in the cell
	  at the specified row and column index to the specified value and notifies listeners
	  that the cell value may have changed. If the cell is not editable (does not represent
	  an editable property) this method will do nothing.
	*/
	
	public void setValueAt(Object value, int row, int column) {
		if (column != 3)
			return;
		
		this.editing.setPropertyValue(value, row);
		super.fireTableCellUpdated(row, column);
	}
}
