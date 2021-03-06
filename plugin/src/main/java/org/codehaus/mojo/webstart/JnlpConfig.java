package org.codehaus.mojo.webstart;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License" );
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.util.Properties;

/**
 * Bean to host part of the JnlpMojo configuration.
 *
 * @author <a href="jerome@coffeebreaks.org">Jerome Lacoste</a>
 * @version $Id: JnlpConfig.java 1908 2006-06-06 13:48:13 +0000 (Tue, 06 Jun 2006) lacostej $
 */
public class JnlpConfig
{

    private String inputTemplateResourcePath;

    private String inputTemplate;

    private String outputFile;

    private String spec;

    private String version;

    // private String codebase;

    private String href;

    private String mainClass;

    /**
     * The path containing any resources which will be added to the webstart artifact
     */
    private File resources;

    private boolean fileVersions;
    
    private boolean groupIdDirs;
    
    private boolean createZip = true;
 
    private boolean copyJars = true;
    
    private boolean versionSnapshots;
    
    private String systemPropertyPrefix = "appSysProp";

	private boolean makeSnapshotsWithNoJNLPVersion;

	private boolean makeJnlpWithVersion = false;

	private String versionSuffix = "";

    public String getSystemPropertyPrefix() {
		return systemPropertyPrefix;
	}

	public void setSystemPropertyPrefix(String systemPropertyPrefix) {
		this.systemPropertyPrefix = systemPropertyPrefix;
	}

	public void setInputTemplateResourcePath( String inputTemplateResourcePath )
    {
        this.inputTemplateResourcePath = inputTemplateResourcePath;
    }

    public void setInputTemplate( String inputTemplate )
    {
        this.inputTemplate = inputTemplate;
    }

    public void setOutputFile( String outputFile )
    {
        this.outputFile = outputFile;
    }

    public void setSpec( String spec )
    {
        this.spec = spec;
    }

    public void setVersion( String version )
    {
        this.version = version;
    }

    /*
    public void setCodebase( String codebase )
    {
        this.codebase = codebase;
    }*/

    public void setHref( String href )
    {
        this.href = href;
    }

    public void setMainClass( String mainClass )
    {
        this.mainClass = mainClass;
    }

    public String getInputTemplateResourcePath()
    {
        return inputTemplateResourcePath;
    }

    public String getInputTemplate()
    {
        return inputTemplate;
    }

    public void setResources( File resources )
    {
        this.resources = resources;
    }

    public String getOutputFile()
    {
        return outputFile;
    }

    public String getSpec()
    {
        return spec;
    }

    public String getVersion()
    {
        return version;
    }

    public File getResources()
    {
        return resources;
    }

    /*
    public String getCodebase()
    {
        return codebase;
    }
    */

    public String getHref()
    {
        return href;
    }

    public String getMainClass()
    {
        return mainClass;
    }
    
    public void setFileVersions(boolean fileVersions) 
    {
		this.fileVersions = fileVersions;
	}
    
    public boolean getFileVersions()
    {
    	return fileVersions;
    }

    public void setVersionSuffix(String suffix)
    {
    	this.versionSuffix  = suffix;
    }
    
    public String getVersionSuffix()
    {
    	return versionSuffix;
    }
    
	public boolean getVersionSnapshots() 
	{
		return versionSnapshots;
	}

	public void setVersionSnapshots(boolean versionSnapshots) 
	{
		this.versionSnapshots = versionSnapshots;
	}

	public boolean getGroupIdDirs() 
	{
		return groupIdDirs;
	}

	public void setGroupIdDirs(boolean groupIdDirs) 
	{
		this.groupIdDirs = groupIdDirs;
	}

	public boolean getCreateZip() 
	{
		return createZip;
	}

	public void setCreateZip(boolean createZip) 
	{
		this.createZip = createZip;
	}    
	
	public boolean getCopyJars()
	{
		return copyJars;
	}
	
	public void setCopyJars(boolean copyJars)
	{
		this.copyJars = copyJars;
	}

	/**
	 * If this is true then generator will create jar files that include 
	 * the snapshot version string.  But do not have a the __V webstart uses
	 * for versions.  This way they can be referred to by jnlp without versions
	 * and let webstart handle the snapshot updating.
	 * 
	 * This property will only turn on the generation of these jar files.  There
	 * will be another property to tell the jnlp generator to use these snapshots
	 * instead of the timestamped versions. 
	 * 
	 * @return
	 */
	public boolean getMakeSnapshotsWithNoJNLPVersion() {
		return this.makeSnapshotsWithNoJNLPVersion;
	}
	
	public void setMakeSnapshotsWithNoJNLPVersion(boolean makeNoVersionSnapshots) {
		this.makeSnapshotsWithNoJNLPVersion = makeNoVersionSnapshots;
	}

	/**
	 * Use the artifactName and version to save a jnlp file
	 * @return
	 */
	public boolean getMakeJnlpWithVersion() {
		return this.makeJnlpWithVersion;
	}
	
	public void setMakeJnlpWithVersion(boolean makeJnlpWithVersion) {
		this.makeJnlpWithVersion = makeJnlpWithVersion;
	}
}
