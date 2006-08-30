/**
 * 
 */
package org.codehaus.mojo.webstart;

import org.codehaus.mojo.webstart.JnlpMojo.Dependencies;

public class ArtifactGroup extends Dependencies
{
	private String name;
	
	public String getName() {
		return name;
	}

	public void setName(String groupName) {
		this.name = groupName;
	}    	
}