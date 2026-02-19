package io.almostrealism.html;

/** The ExternalScript class. */
public class ExternalScript implements HTMLContent {
	private String file;
	
	public ExternalScript(String script) { file = script; }
	
	@Override
	public String toHTML() { return "<script src=\"" + file + "\"></script>"; }
}
