package org.almostrealism.html;

public class ExternalScript implements HTMLContent {
	private String file;
	
	public ExternalScript(String script) { file = script; }
	
	public String toHTML() { return "<script src=\"" + file + "\"></script>"; }
}
