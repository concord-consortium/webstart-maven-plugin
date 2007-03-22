package org.codehaus.mojo.webstart.generator;

/*
 * Copyright 2005 Nick C
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

import org.apache.tools.ant.taskdefs.PathConvert.MapEntry;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.log.NullLogSystem;
import org.apache.maven.artifact.Artifact;
import org.codehaus.mojo.webstart.JnlpConfig;
import org.codehaus.mojo.webstart.JnlpMojo;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.List;
import java.util.Set;

/**
 * Generates a JNLP deployment descriptor
 *
 * @author ngc
 * @author <a href="jerome@coffeebreaks.org">Jerome Lacoste</a>
 */
public class Generator
{
    private VelocityEngine engine = new VelocityEngine();

    private JnlpMojo config;

    private Template template;

    private File outputFile;

    /**
     * @param task
     * @param resourceLoaderPath  used to find the template in conjunction to inputFileTemplatePath
     * @param outputFile
     * @param inputFileTemplatePath relative to resourceLoaderPath
     */
    public Generator( JnlpMojo task, File resourceLoaderPath, File outputFile, String inputFileTemplatePath )
    {
        this.config = task;

        this.outputFile = outputFile;
        //initialise the resource loader to use the class loader
        Properties props = new Properties();

        props.setProperty( VelocityEngine.RUNTIME_LOG_LOGSYSTEM_CLASS,
                           "org.apache.velocity.runtime.log.NullLogSystem" );
        props.setProperty( "file.resource.loader.path", resourceLoaderPath.getAbsolutePath() );

        // System.out.println("OUHHHHH " + resourceLoaderPath.getAbsolutePath());

        // props.setProperty( VelocityEngine.RESOURCE_LOADER, "classpath" );
        // props.setProperty( "classpath." + VelocityEngine.RESOURCE_LOADER + ".class",
        //                   ClasspathResourceLoader.class.getName() );
        try
        {
            //initialise the Velocity engine
            engine.setProperty( "runtime.log.logsystem", new NullLogSystem() );
            engine.init( props );
        }
        catch ( Exception e )
        {
            IllegalArgumentException iae = new IllegalArgumentException( "Could not initialise Velocity" );
            iae.initCause( e );
            throw iae;
        }
        //set the template
        if ( ! engine.templateExists( inputFileTemplatePath ) )
        {
            System.out.println( "Template not found!! ");
        }
        try
        {
            this.template = engine.getTemplate( inputFileTemplatePath );
        }
        catch ( Exception e )
        {
            IllegalArgumentException iae =
                new IllegalArgumentException( "Could not load the template file from '" + inputFileTemplatePath + "'" );
            iae.initCause( e );
            throw iae;
        }
    }
    
    public void generate()
        throws Exception
    {
    	FileWriter writer = new FileWriter( outputFile );
    	generate(writer, outputFile.getName(), 
    			config.getVersionedArtifactName());
    }
    
    public void generate(Writer writer, String outputFileName,
    		String versionedArtifactName)
    	throws Exception
    {
        VelocityContext context = new VelocityContext();

        // add the maven project so the template can use it
        context.put("project", config.getProject());
        
        Map jnlpArtifactGroups = config.getJnlpArtifactGroups();
        
        // add the default dependencies list
        List artifacts = (List)jnlpArtifactGroups.get(JnlpMojo.DEFAULT_ARTIFACT_GROUP); 
        
        context.put( "dependencies", 
        		getDependenciesText( config, artifacts ) );

        Map jnlpTextMap = new HashMap();
        
        Set entries = jnlpArtifactGroups.entrySet();
        for(Iterator i = entries.iterator(); i.hasNext(); ) {
        	Map.Entry entry = (Map.Entry)i.next();
        	String jnlpText = 
        		getDependenciesText(config, (List)entry.getValue());
        	jnlpTextMap.put(entry.getKey(), jnlpText);
        }
        
    	context.put( "artifactGroup", jnlpTextMap);

    	context.put( "systemProperties", getPropertiesText(config));    	

    	context.put( "outputFile", outputFileName );
        context.put( "mainClass", config.getJnlp().getMainClass() );
        context.put( "versionedArtifactName", versionedArtifactName );

        try
        {
            //parse the template
            //StringWriter writer = new StringWriter();
            template.merge( context, writer );
            writer.flush();
        }
        catch ( Exception e )
        {
            throw new Exception( "Could not generate the template " + template.getName() + ": " + e.getMessage(), e );
        }
        finally
        {
            writer.close();
        }
    }

    static String getPropertiesText( JnlpMojo config)
    {
    	String sysPropPrefix = config.getJnlp().getSystemPropertyPrefix() + ".";
    	
    	StringBuffer buffer = new StringBuffer();
    	
    	Properties projectProperties = config.getProject().getProperties();
    	
    	Set entries = projectProperties.entrySet();
    	
    	for(Iterator i=entries.iterator(); i.hasNext(); ) {
    		Map.Entry entry = (Map.Entry)i.next();
    		String key = (String)entry.getKey();

    		if(!key.startsWith(sysPropPrefix)){
    			continue;
    		}

    		key = key.substring(sysPropPrefix.length());
    		String value = (String)entry.getValue();
    		
    		buffer.append( "<property name=\"");
    		buffer.append( key );
    		buffer.append( "\" value=\"");
    		buffer.append( value );
    		buffer.append( "\"/>");
    		
    		buffer.append( "\n" );    			
    	}
    	    	
    	if(buffer.length() == 0) {
    		return null;
    	}
    	
    	// strip off the last return
    	buffer.setLength(buffer.length() - 1);    	
    	
    	return buffer.toString();
    }
    
    static String getDependenciesText( JnlpMojo config, List artifacts)
    {	
    	if (artifacts.size() == 0) {
    		return null;
    	}

    	String dependenciesText = "";
    	StringBuffer buffer = new StringBuffer( 100 * artifacts.size() );
    	for ( int i = 0; i < artifacts.size(); i++ )
    	{
    		Artifact artifact = (Artifact) artifacts.get( i );

    		if(artifact.getType().equals("jar")) {                
    			buffer.append( "<jar href=\"" );
    		} else if(artifact.getType().equals("nar")) {
    			buffer.append( "<nativelib href=\"");
    		}
    		buffer.append(config.getArtifactJnlpDir(artifact));

    		buffer.append(config.getArtifactJnlpHref(artifact));
    		buffer.append("\"");
    		if(config.getJnlp() != null && config.getJnlp().getFileVersions()) {
    			buffer.append( " version=\"" ).append(config.getArtifactJnlpVersion(artifact)).append( "\"");            		
    		} 

    		if ( config.isArtifactWithMainClass( artifact ) )
    		{
    			buffer.append( " main=\"true\"" );
    		}
    		buffer.append( "/>" );
    		if ( i != (artifacts.size()-1) ) {
        		buffer.append( "\n" );    			
    		}
    		
    	}
    	return buffer.toString();
    }

}
