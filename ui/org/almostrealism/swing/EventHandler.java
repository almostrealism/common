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


import java.util.Enumeration;
import java.util.Vector;

import org.almostrealism.swing.dialogs.DialogCloseEvent;

/**
  An EventHandler object provides an interface for comunication between EventGenerators and EventListeners.
*/

public class EventHandler {
  private Vector listeners;

	/**
	  Constructs a new EventHandler object with no listeners.
	*/
	
	public EventHandler() {
		this.listeners = new Vector();
	}
	
	/**
	 * Adds the specified EventListener to this EventHandler. The listener will be notified through its
	 * eventFired method when an event has been fired.
	 */
	public void addListener(EventListener listener) { this.listeners.addElement(listener); }
	
	/**
	 * Removes the specified EventListener from this EventHandler.
	 */
	public void removeListener(int index) { this.listeners.removeElementAt(index); }
	
	/**
	 * Removes the specified EventListener from this EventHandler.
	 */
	public void removeListener(EventListener listener) { this.listeners.remove(listener); }
	
	/**
	 * Returns the specified EventListener.
	 */
	public EventListener getListener(int index) { return (EventListener)this.listeners.elementAt(index); }
	
	/**
	 * Returns the number of EventListeners currently registered with this EventHandler.
	 */
	public int getTotalListeners() { return this.listeners.size(); }
	
	/**
	 * Notifies all current EventListeners that an event has been fired. If the event is an instance of
	 * DialogCloseEvent and the Dialog object stored by the event is a registered as a listener with this
	 * EventHandler object, the dialog will be removed. If the dialog is registered more than once,
	 * only the first instance will be removed.
	 */
	public void fireEvent(Event event) {
		if (event instanceof DialogCloseEvent) {
			Dialog dialog = ((DialogCloseEvent)event).getDialog();
			
			int index = -1;
			int i = 0;
			
			Enumeration en = this.listeners.elements();
			
			i: while (en.hasMoreElements()) {
				if (en.nextElement() == dialog) {
					index = i;
					break i;
				}
				
				i++;
			}
			
			if (index >= 0)
				this.removeListener(index);
		}
		
		for(int i = 0; i < this.getTotalListeners(); i++) {
			this.getListener(i).eventFired(event);
		}
	}
}
