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

import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;

import javax.swing.JFrame;
import javax.swing.JPanel;

/**
 * {@link DragSupport} is used to make {@link JFrame}s draggable when clicking
 * and dragging on a panel within the {@link JFrame}. It must be added as both
 * a {@link MouseListener} and {@link MouseMotionListener}.
 * 
 * @author  Michael Murray
 */
public class DragSupport implements MouseListener, MouseMotionListener {
    private Point initialClick;
    private JFrame frame;
    private JPanel panel;
    
    public DragSupport(JFrame frame, JPanel panel) {
    	this.frame = frame;
    	this.panel = panel;
    }
    
    @Override
    public void mouseDragged(MouseEvent e) {
        int thisX = frame.getLocation().x;
        int thisY = frame.getLocation().y;
        
        int xMoved = (thisX + e.getX()) - (thisX + initialClick.x);
        int yMoved = (thisY + e.getY()) - (thisY + initialClick.y);
        
        int X = thisX + xMoved;
        int Y = thisY + yMoved;
        frame.setLocation(X, Y);
    }
    
    @Override
    public void mousePressed(MouseEvent e) {
        initialClick = e.getPoint();
        panel.getComponentAt(initialClick);
    }

	@Override
	public void mouseMoved(MouseEvent e) { }
	@Override
	public void mouseClicked(MouseEvent e) { }
	@Override
	public void mouseReleased(MouseEvent e) { }
	@Override
	public void mouseEntered(MouseEvent e) { }
	@Override
	public void mouseExited(MouseEvent e) { }
}
