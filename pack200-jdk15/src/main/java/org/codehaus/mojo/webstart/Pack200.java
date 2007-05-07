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

import com.sun.tools.apache.ant.pack200.Pack200Task;
import com.sun.tools.apache.ant.pack200.Unpack200Task;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.tools.ant.Project;

import java.io.File;
import java.io.FileFilter;

/**
 * Handles pack200 operations.
 *
 * @author <a href="jerome@coffeebreaks.org">Jerome Lacoste</a>
 */
public class Pack200
{

    public static void packJars( File directory, FileFilter jarFileFilter, boolean gzip ) 
    	throws MojoExecutionException
    {
        File[] jarFiles = directory.listFiles( jarFileFilter );

        packJars(jarFiles, gzip);
    }
	
    public static void packJars( File [] jarFiles, boolean gzip ) 
    	throws MojoExecutionException
    {
        for ( int i = 0; i < jarFiles.length; i++ )
        {
        	packJar(jarFiles[i], gzip);
        }
    }

    public static void packJar(File jarFile, boolean gzip)
    	throws MojoExecutionException
    {
        final String extension = gzip ? ".pack.gz" : ".pack";

        File pack200Jar = new File( jarFile.getParentFile(), jarFile.getName() + extension );

        if ( pack200Jar.exists() )
        {
        	pack200Jar.delete();
        }        
        
        Pack200Task packTask = new Pack200Task();
        packTask.setProject( new Project() );
        packTask.setDestfile( pack200Jar );
        packTask.setSrc( jarFile );
        packTask.setGZIPOutput( gzip );
        packTask.execute();

        // Do some basic checking of the output.  Becaus it seems like
        // the packTask doesn't do this
        if(!pack200Jar.exists() || pack200Jar.length() <= 0){
        	// there was an error of some kind
        	throw new MojoExecutionException("Pack200 error");
        }
        
        pack200Jar.setLastModified( jarFile.lastModified() );
    }
    
    public static void unpackJars( File directory, FileFilter pack200FileFilter )
    {
        File[] packFiles = directory.listFiles( pack200FileFilter );
        
        unpackJars(packFiles);
    }
    
    public static void unpackJars( File[] packFiles )
    {
        // getLog().debug( "unpackJars for " + directory );
        for ( int i = 0; i < packFiles.length; i++ )
        {
        	unpackJar(packFiles[i]);        	
        }
    }
    
    public static void unpackJar(File packFile)
    {
    	Unpack200Task unpackTask;
    	final String packedJarPath = packFile.getAbsolutePath();
    	int extensionLength = packedJarPath.endsWith( ".jar.pack.gz" ) ? 8 : 5;
    	String jarFileName = packedJarPath.substring( 0, packedJarPath.length() - extensionLength );
    	File jarFile = new File( jarFileName );

    	if ( jarFile.exists() )
    	{
    		jarFile.delete();
    	}
    	unpackTask = new Unpack200Task();
    	unpackTask.setProject( new Project() );
    	unpackTask.setDest( jarFile );
    	unpackTask.setSrc( packFile );
    	try {
    		unpackTask.execute();
    	} catch (Throwable e){
    		throw new RuntimeException("Error Unpacking: " + packFile, 
    				e);
    	}
    	jarFile.setLastModified( packFile.lastModified() );

    }
}
