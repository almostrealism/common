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

/*
 * Copyright (C) 2005  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 *
 */

package org.almostrealism.io;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * @author Mike Murray
 */
public class RSSFeed {
	public static class Item {
		private String title, text, image;
		
		public Item(String title, String text) {
			this.title = title;
			this.text = text;
		}
		
		public void setImage(String image) { this.image = image; }
		public String getImage() { return this.image; }
		
		public String toString() { return RSSFeed.generateItem(this.title, this.text, this.image, new Date()); }
	}
	
	public static final String lineBreak = "&lt;br /&gt;";
	public static final String startHtml = "<![CDATA[";
	public static final String endHtml = "]]>";
	
	private String title, desc, link;
	private List items;
	
	private SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");
	
	public RSSFeed(String title, String desc) {
		this.title = title;
		this.desc = desc;
		
		this.items = new ArrayList();
	}
	
	public void setLink(String url) { this.link = url; }
	public String getLink() { return this.link; }
	
	public synchronized void postItem(Item i) { this.items.add(i.toString()); }
	
	public synchronized void write(PrintStream p, int ttl) {
		p.println("<?xml version=\"1.0\" encoding=\"utf-8\"?><rss version=\"2.0\"><channel>");
		p.println("<title>" + this.title + "</title>");
		if (this.link != null) p.println("<link>" + this.link + "</link>");
		p.println("<description>" + this.desc + "</description>");
		p.println("<lastBuildDate>" + format.format(new Date()) + "</lastBuildDate>");
		p.println("<ttl>" + ttl + "</ttl>");
		p.println("<language>en-us</language>");
		
		Iterator itr = this.items.iterator();
		while (itr.hasNext()) p.println(itr.next());
		
		p.println("</channel></rss>");
	}
	
	public synchronized static String generateItem(String title, String text, String image, Date d) {
		StringBuffer b = new StringBuffer();
		
		b.append("<item><title>");
		b.append(title);
		b.append("</title><pubDate>");
		b.append(new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z").format(d));
		b.append("</pubDate><description>");
		b.append(text);
		
		if (image != null) {
			b.append(RSSFeed.lineBreak);
			b.append(RSSFeed.startHtml);
			b.append("<img src=\"");
			b.append(image);
			b.append("\" />");
			b.append(RSSFeed.endHtml);
		}
		
		b.append("</description></item>");
		
		return b.toString();
	}
}
