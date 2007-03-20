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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.apache.commons.lang.SystemUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ExcludesArtifactFilter;
import org.apache.maven.artifact.resolver.filter.IncludesArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.plugin.jar.JarSignMojo;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.settings.Settings;
import org.codehaus.mojo.keytool.GenkeyMojo;
import org.codehaus.mojo.webstart.generator.Generator;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;

/**
 * Packages a jnlp application.
 * <p/>
 * The plugin tries to not re-sign/re-pack if the dependent jar hasn't changed.
 * As a consequence, if one modifies the pom jnlp config or a keystore, one should clean before rebuilding.
 *
 * @author <a href="jerome@coffeebreaks.org">Jerome Lacoste</a>
 * @version $Id: JnlpMojo.java 1908 2006-06-06 13:48:13 +0000 (Tue, 06 Jun 2006) lacostej $
 * @goal jnlp
 * @phase package
 * @requiresDependencyResolution runtime
 * @requiresProject
 * @inheritedByDefault true
 * @todo refactor the common code with javadoc plugin
 * @todo how to propagate the -X argument to enable verbose?
 * @todo initialize the jnlp alias and dname.o from pom.artifactId and pom.organization.name
 */
public class JnlpMojo
    extends AbstractMojo
{
	/**
	 * These shouldn't be necessary, but just incase the main ones in 
	 * maven change.  They can be found in SnapshotTransform class
	 */
    private static final TimeZone UTC_TIME_ZONE = TimeZone.getTimeZone( "UTC" );

    private static final String UTC_TIMESTAMP_PATTERN = "yyyyMMdd.HHmmss";

    /**
     * Directory to create the resulting artifacts
     *
     * @parameter expression="${project.build.directory}/jnlp"
     * @required
     */
    protected File workDirectory;

    /**
     * The Zip archiver.
     *
     * @parameter expression="${component.org.codehaus.plexus.archiver.Archiver#zip}"
     * @required
     */
    private ZipArchiver zipArchiver;

    /**
     * Project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * Xxx
     *
     * @parameter
     */
    private JnlpConfig jnlp;

    /**
     * Xxx
     *
     * @parameter
     */
    private Dependencies dependencies;

	public static class Dependencies
    {
        private List includes;

        private List excludes;

        private List includeClassifiers;

        private List excludeClassifiers;
        
		public List getIncludes()
        {
            return includes;
        }

        public void setIncludes( List includes )
        {
            this.includes = includes;
        }

		public List getIncludeClassifiers() {
			return includeClassifiers;
		}

		public void setIncludeClassifiers(List includeClassifiers) {
			this.includeClassifiers = includeClassifiers;
		}        
        
        public List getExcludes()
        {
            return excludes;
        }

        public void setExcludes( List excludes )
        {
            this.excludes = excludes;
        }

        public List getExcludeClassifiers() {
			return excludeClassifiers;
		}

		public void setExcludeClassifiers(List excludeClassifiers) {
			this.excludeClassifiers = excludeClassifiers;
		}


    }

    /**
     * Xxx
     * 
     * @parameter 
     */
    private List artifactGroups;
    
    /**
     * Xxx
     *
     * @parameter
     */
    private SignConfig sign;

    public static class KeystoreConfig
    {
        private boolean delete;

        private boolean gen;

        public boolean isDelete()
        {
            return delete;
        }

        public void setDelete( boolean delete )
        {
            this.delete = delete;
        }

        public boolean isGen()
        {
            return gen;
        }

        public void setGen( boolean gen )
        {
            this.gen = gen;
        }
    }

    /**
     * Xxx
     *
     * @parameter
     */
    private KeystoreConfig keystore;

    /**
     * Xxx
     *
     * @parameter default-value="false"
     */
    // private boolean usejnlpservlet;

    /**
     * Xxx
     *
     * @parameter default-value="true"
     */
    private boolean verifyjar;

    /**
     * Enables pack200. Requires SDK 5.0.
     *
     * @parameter default-value="false"
     */
    private boolean pack200;

    /**
     * Xxx
     *
     * @parameter default-value="false"
     */
    private boolean gzip;

    /**
     * Enable verbose.
     *
     * @parameter expression="${verbose}" default-value="false"
     */
    private boolean verbose;

    /**
     * @parameter expression="${basedir}"
     * @required
     * @readonly
     */
    private File basedir;

    /**
     * Artifact resolver, needed to download source jars for inclusion in classpath.
     *
     * @parameter expression="${component.org.apache.maven.artifact.resolver.ArtifactResolver}"
     * @required
     * @readonly
     * @todo waiting for the component tag
     */
    private ArtifactResolver artifactResolver;

    /**
     * Artifact factory, needed to download source jars for inclusion in classpath.
     *
     * @parameter expression="${component.org.apache.maven.artifact.factory.ArtifactFactory}"
     * @required
     * @readonly
     * @todo waiting for the component tag
     */
    private ArtifactFactory artifactFactory;

    /**
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository localRepository;

    /**
     * @parameter expression="${component.org.apache.maven.project.MavenProjectHelper}
     */
    private MavenProjectHelper projectHelper;

    /**
     * The current user system settings for use in Maven. This is used for
     * <br/>
     * plugin manager API calls.
     *
     * @parameter expression="${settings}"
     * @required
     * @readonly
     */
    private Settings settings;

    /**
     * The plugin manager instance used to resolve plugin descriptors.
     *
     * @component role="org.apache.maven.plugin.PluginManager"
     */
    private PluginManager pluginManager;

    private class CompositeFileFilter
        implements FileFilter
    {
        private List fileFilters = new ArrayList();

        CompositeFileFilter( FileFilter filter1, FileFilter filter2 )
        {
            fileFilters.add( filter1 );
            fileFilters.add( filter2 );
        }

        public boolean accept( File pathname )
        {
            for ( int i = 0; i < fileFilters.size(); i++ )
            {
                if ( ! ( (FileFilter) fileFilters.get( i ) ).accept( pathname ) )
                {
                    return false;
                }
            }
            return true;
        }

    }

    /**
     * The maven-xbean-plugin can't handle anon inner classes
     * 
     * @author scott
     *
     */
    private class ModifiedFileFilter 
    implements FileFilter
    {
    	public boolean accept( File pathname )
    	{
    		boolean modified = pathname.lastModified() > getStartTime();
    		getLog().debug( "File: " + pathname.getName() + " modified: " + modified );
    		getLog().debug( "lastModified: " + pathname.lastModified() + " plugin start time " + getStartTime() );
    		return modified;
    	}

    }
    private FileFilter modifiedFileFilter = new ModifiedFileFilter();

    /**
	 * @author scott
	 *
	 */
	private final class JarFileFilter implements FileFilter {
		public boolean accept( File pathname )
		{
		    return pathname.isFile() && pathname.getName().endsWith( ".jar" );
		}
	}
    private FileFilter jarFileFilter = new JarFileFilter();

	/**
	 * @author scott
	 *
	 */
	private final class Pack200FileFilter implements FileFilter {
		public boolean accept( File pathname )
		{
		    return pathname.isFile() &&
		        ( pathname.getName().endsWith( ".jar.pack.gz" ) || pathname.getName().endsWith( ".jar.pack" ) );
		}
	}
    private FileFilter pack200FileFilter = new Pack200FileFilter();

    // the jars to sign and pack are selected if they are newer than the plugin start.
    // as the plugin copies the new versions locally before signing/packing them
    // we just need to see if the plugin copied a new version
    // We achieve that by only filtering files modified after the plugin was started
    // FIXME we may want to also resign/repack the jars if other files (the pom, the keystore config) have changed
    // today one needs to clean...
    private FileFilter updatedJarFileFilter = new CompositeFileFilter( jarFileFilter, modifiedFileFilter );

    private FileFilter updatedPack200FileFilter = new CompositeFileFilter( pack200FileFilter, modifiedFileFilter );

    /**
     * the artifacts packaged in the webstart app. *
     */
    private List packagedJnlpArtifacts = new ArrayList();

    /**
     * A map of groups of dependencies for the jnlp file.
     * the key of each group is string which can be referenced in   
     */
    private Map jnlpArtifactGroups = new HashMap();
    
    private ArrayList copiedJnlpArtifacts = new ArrayList();
    
    private Artifact artifactWithMainClass;

    // initialized by execute
    private long startTime;

	private String jnlpBuildVersion;

    private long getStartTime()
    {
        if ( startTime == 0 )
        {
            throw new IllegalStateException( "startTime not initialized" );
        }
        return startTime;
    }

    public void execute()
        throws MojoExecutionException
    {

        checkInput();

        if(jnlp == null) {
        	getLog().debug( "skipping project because no jnlp element was found");
        	return;
        }
        
        // interesting: copied files lastModified time stamp will be rounded.
        // We have to be sure that the startTime is before that time...
        // rounding to the second - 1 millis should be sufficient..
        startTime = System.currentTimeMillis() - 1000;

        File workDirectory = getWorkDirectory();
        getLog().debug( "using work directory " + workDirectory );
        //
        // prepare layout
        //
        if ( !workDirectory.exists() && !workDirectory.mkdirs() )
        {
            throw new MojoExecutionException( "Failed to create: " + workDirectory.getAbsolutePath() );
        }

        try
        {
            File resourcesDir = getJnlp().getResources();
            if ( resourcesDir == null )
            {
                resourcesDir = new File( project.getBasedir(), "src/main/jnlp" );
            }
            copyResources( resourcesDir, workDirectory );

            artifactWithMainClass = null;

            processDependencies();

            buildArtifactGroups();
            
            if ( artifactWithMainClass == null )
            {
            	getLog().info( "skipping project because no main class: " + 
            			jnlp.getMainClass() + " was found");
            	return;
            	/*
            	throw new MojoExecutionException(
                    "didn't find artifact with main class: " + jnlp.getMainClass() + ". Did you specify it? " );
                    */
            }

            // native libsi
            // FIXME

            /*
            for( Iterator it = getNativeLibs().iterator(); it.hasNext(); ) {
                Artifact artifact = ;
                Artifact copiedArtifact = 

                // similar to what we do for jars, except that we must pack them into jar instead of copying.
                // them
                    File nativeLib = artifact.getFile()
                    if(! nativeLib.endsWith( ".jar" ) ){
                        getLog().debug("Wrapping native library " + artifact + " into jar." );
                        File nativeLibJar = new File( applicationFolder, xxx + ".jar");
                        Jar jarTask = new Jar();
                        jarTask.setDestFile( nativeLib );
                        jarTask.setBasedir( basedir );
                        jarTask.setIncludes( nativeLib );
                        jarTask.execute();

                        nativeLibJar.setLastModified( nativeLib.lastModified() );
              
                        copiedArtifact = new ....
                    } else {
                        getLog().debug( "Copying native lib " + artifact );
                        copyFileToDirectory( artifact.getFile(), applicationFolder );
  
                        copiedArtifact = artifact;
                    }
                    copiedNativeArtifacts.add( copiedArtifact );
                }
            }
            */

            //
            // pack200 and jar signing
            //
            if ( ( pack200 || sign != null ) && getLog().isDebugEnabled() )
            {
                logCollection(
                    "Some dependencies may be skipped. Here's the list of the artifacts that should be signed/packed: ",
                    copiedJnlpArtifacts );
            }

            File [] copiedJars = new File [copiedJnlpArtifacts.size()];
            copiedJnlpArtifacts.toArray(copiedJars);

            if ( sign != null )
            {

                if ( keystore != null && keystore.isGen() )
                {
                    if ( keystore.isDelete() )
                    {
                        deleteKeyStore();
                    }
                    genKeyStore();
                }
                
                if ( pack200 )
                {
                    // http://java.sun.com/j2se/1.5.0/docs/guide/deployment/deployment-guide/pack200.html
                    // we need to pack then unpack the files before signing them
                	
                	// There is no need to gz them if we are just going to uncompress them
                	// again. (gz isn't losy)
                    Pack200.packJars( copiedJars, false );
                    
                    File [] packedJars = new File[copiedJars.length];
                    for(int i=0; i<packedJars.length; i++){
                    	packedJars[i] = new File(copiedJars[i].getAbsolutePath() + ".pack");
                    }
                    Pack200.unpackJars( packedJars );
                    
                    // specs says that one should do it twice when there are unsigned jars??
                    // I don't know about the unsigned part, but I found a jar
                    // that had to be packed, unpacked, packed, and unpacked before
                    // it could be signed and packed correctly.
                    // I suppose the best way to do this would be to try the signature
                    // and if it fails then pack it again, instead of packing and unpack
                    // every single jar
                    if(sign.getPack200Twice()) {
                    	// the pack jars verifies the signature of the jar before
                    	// it packs it.  So the jar needs to be signed first.
                    	// unless there is a way to turn off this verification
                        int signedJars = signJars( workDirectory, copiedJars );
                        if ( signedJars != copiedJnlpArtifacts.size() )
                        {
                            throw new IllegalStateException(
                                "the number of signed artifacts differ from the number of modified artifacts. Implementation error" );
                        }
                    	
                    	
                        Pack200.packJars( copiedJars, false );
                        Pack200.unpackJars( packedJars );                    	
                    }
                    
                    
                    // Pack200.unpackJars( applicationDirectory, updatedPack200FileFilter );
                }

                int signedJars = signJars( workDirectory, copiedJars );
                if ( signedJars != copiedJnlpArtifacts.size() )
                {
                    throw new IllegalStateException(
                        "the number of signed artifacts differ from the number of modified artifacts. Implementation error" );
                }
            }
            if ( pack200 )
            {
                getLog().debug( "packing jars" );
                Pack200.packJars( copiedJars, this.gzip );
            }

            /*
            if (sign.getPack200SignLoop()) {
            	// unpack the jar then verify it with the jarsigner, if it fails
            	// repeat
                File [] packedGzJars = new File[copiedJars.length];
                for(int i=0; i<packedGzJars.length; i++){
                	packedGzJars[i] = new File(copiedJars[i].getAbsolutePath() + ".pack.gz");
                }

                // unpack all the jars
                Pack200.unpackJars(packedGzJars);
                                
                for(int i=0; i<copiedJars.length; i++){
                	JarSignVerifyMojo verifier = new JarSignVerifyMojo();
                	verifier.setJarPath(copiedJars[i]);
                	verifier.setLog(getLog());
                	verifier.setErrorWhenNotSigned(false);
                	
                	verifier.execute();
                	if(!verifier.isSigned()){
                		// unpacking failed.  so we need to repack
                		
                	}
                }
            }
            */

            // make the snapshot copies if necessary
            if(jnlp.getMakeSnapshotsWithNoJNLPVersion()) {
            	for(int i=0; i<packagedJnlpArtifacts.size(); i++){
            		Artifact artifact = (Artifact)packagedJnlpArtifacts.get(i);
            		if(!artifact.isSnapshot()){
            			continue;
            		}
            		
            		String jarBaseName = getArtifactJnlpBaseName(artifact);

            		String snapshot_outputName = jarBaseName + "-" + 
            		artifact.getBaseVersion() + ".jar";

            		File targetDirectory = getArtifactJnlpDirFile(artifact);
            			
            		
                    File versionedFile = 
                    	new File(targetDirectory, getArtifactJnlpName(artifact));
                    
            		// this is method should reduce the number of times a file 
            		// needs to be downloaded by an applet or webstart.  However
            		// it isn't very safe if multiple users are running this. 
            		// this method will be comparing the date of the file in the
            		// current users local maven repo with the last generated 
            		// file.  If a new user just starts using maven all of their
            		// local repo files will be newer.  
            		// This file won't be signed, we should copy the version
            		copyFileToDirectoryIfNecessary( versionedFile, targetDirectory, 
            				snapshot_outputName );
            	}
            }
            
            File jnlpDirectory = workDirectory;
            if(jnlp.getGroupIdDirs()) {
            	// store the jnlp in the same layout as the jar files
            	// if groupIdDirs is true
            	jnlpDirectory = 
            		getArtifactJnlpDirFile(getProject().getArtifact());
            }
            generateJnlpFile( jnlpDirectory );

            if(jnlp.getCreateZip()) {
            	// package the zip. Note this is very simple. Look at the JarMojo which does more things.
            	// we should perhaps package as a war when inside a project with war packaging ?
            	File toFile = new File( project.getBuild().getDirectory(), project.getBuild().getFinalName() + ".zip" );
            	if ( toFile.exists() )
            	{
            		getLog().debug( "deleting file " + toFile );
            		toFile.delete();
            	}
            	zipArchiver.addDirectory( workDirectory );
            	zipArchiver.setDestFile( toFile );
            	getLog().debug( "about to call createArchive" );
            	zipArchiver.createArchive();

            	// maven 2 version 2.0.1 method
            	projectHelper.attachArtifact( project, "zip", toFile );
            }
        }
        catch ( MojoExecutionException e )
        {
            throw e;
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failure to run the plugin: ", e );
            /*
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter( sw, true );
            e.printStackTrace( pw );
            pw.flush();
            sw.flush();

            getLog().debug( "An error occurred during the task: " + sw.toString() );
            */
        }

    }

    private void copyResources( File resourcesDir, File workDirectory )
        throws IOException
    {
        if ( ! resourcesDir.exists() )
        {
            getLog().info( "No resources found in " + resourcesDir.getAbsolutePath() );
        }
        else
        {
            if ( ! resourcesDir.isDirectory() )
            {
                getLog().debug( "Not a directory: " + resourcesDir.getAbsolutePath() );
            }
            else
            {
                getLog().debug( "Copying resources from " + resourcesDir.getAbsolutePath() );

                // hopefully available from FileUtils 1.0.5-SNAPSHOT
                // FileUtils.copyDirectoryStructure( resourcesDir , workDirectory );

                // this may needs to be parametrized somehow
                String excludes = concat( DirectoryScanner.DEFAULTEXCLUDES, ", " );
                copyDirectoryStructure( resourcesDir, workDirectory, "**", excludes );
            }
        }
    }

    private static String concat( String[] array, String delim )
    {
        StringBuffer buffer = new StringBuffer();
        for ( int i = 0; i < array.length; i++ )
        {
            if ( i > 0 )
            {
                buffer.append( delim );
            }
            String s = array[i];
            buffer.append( s ).append( delim );
        }
        return buffer.toString();
    }

    private void copyDirectoryStructure( File sourceDirectory, File destinationDirectory, String includes,
                                         String excludes )
        throws IOException
    {
        if ( ! sourceDirectory.exists() )
        {
            return;
        }

        List files = FileUtils.getFiles( sourceDirectory, includes, excludes );

        for ( Iterator i = files.iterator(); i.hasNext(); )
        {
            File file = (File) i.next();

            getLog().debug( "Copying " + file + " to " + destinationDirectory );

            String path = file.getAbsolutePath().substring( sourceDirectory.getAbsolutePath().length() + 1 );

            File destDir = new File( destinationDirectory, path );

            getLog().debug( "Copying " + file + " to " + destDir );

            if ( file.isDirectory() )
            {
                destDir.mkdirs();
            }
            else
            {
                FileUtils.copyFileToDirectory( file, destDir.getParentFile() );
            }
        }
    }

    private ArtifactFilter createArtifactFilter(Dependencies dependencies)
    {
        AndArtifactFilter filter = new AndArtifactFilter();

        if ( dependencies == null) {
        	return filter;
        }
        
        if ( dependencies.getIncludes() != null && !dependencies.getIncludes().isEmpty() )
        {
            filter.add( new IncludesArtifactFilter( dependencies.getIncludes() ) );
        }
        
        if ( dependencies.getIncludeClassifiers() != null && !dependencies.getIncludeClassifiers().isEmpty() )
        {
        	final List classifierList = dependencies.getIncludeClassifiers();
        	filter.add( new ArtifactFilter (){
        		public boolean include(Artifact artifact) {
        			if(!artifact.hasClassifier()) {
        				return false;
        			}
        			String classifer = artifact.getClassifier();
        			
        			if (classifierList.contains(classifer)) {
        				return true;
        			}
        			
        			return false;
        			
        		};
        	});
        }
        
        if ( dependencies.getExcludeClassifiers() != null && !dependencies.getExcludeClassifiers().isEmpty() )
        {
        	final List classifierList = dependencies.getExcludeClassifiers();
        	filter.add( new ArtifactFilter (){
        		public boolean include(Artifact artifact) {
        			String classifier = artifact.getClassifier();
        			
        			// The artifact has no classifier so this excludes rule doesn't 
        			// apply
        			if(classifier == null || classifier.length() == 0) {
        				return true;
        			}
        			
        			// special pattern hack so excluding * can be handled
        			// if star is used then any artifact with a classifier is excluded
        			if(classifierList.contains("*")) {
        				return false;
        			}
        			
        			if (classifierList.contains(classifier)) {
        				return false;
        			}
        			
        			return true;
        			
        		};
        	});
        }
                
        if ( dependencies.getExcludes() != null && !dependencies.getExcludes().isEmpty() )
        {
            filter.add( new ExcludesArtifactFilter( dependencies.getExcludes() ) );
        }

        return filter;
    }
    
    /**
     * Iterate through all the top level and transitive dependencies declared in the project and
     * collect all the runtime scope dependencies for inclusion in the .zip and signing.
     *
     * @throws IOException
     */
    private void processDependencies()
        throws IOException
    {

        processDependency( getProject().getArtifact(), packagedJnlpArtifacts );

        ArtifactFilter filter = createArtifactFilter(dependencies);

        Collection artifacts = getProject().getArtifacts();

        for ( Iterator it = artifacts.iterator(); it.hasNext(); )
        {
            Artifact artifact = (Artifact) it.next();
            if ( filter.include( artifact ) )
            {
                processDependency( artifact , packagedJnlpArtifacts);
            }
        }
    }

    private void processDependency( Artifact artifact , List artifactList)
        throws IOException
    {
        // TODO: scope handler
        // Include runtime and compile time libraries
        if ( !Artifact.SCOPE_PROVIDED.equals( artifact.getScope() ) &&
            !Artifact.SCOPE_TEST.equals( artifact.getScope() ) )
        {
            String type = artifact.getType();
            if ( "jar".equals( type ) || "nar".equals( type ) )
            {

                // FIXME when signed, we should update the manifest.
                // see http://www.mail-archive.com/turbine-maven-dev@jakarta.apache.org/msg08081.html
                // and maven1: maven-plugins/jnlp/src/main/org/apache/maven/jnlp/UpdateManifest.java
                // or shouldn't we?  See MOJO-7 comment end of October.
                final File toCopy = artifact.getFile();

                if ( toCopy == null )
                {
                    getLog().error( "artifact with no file: " + artifact );
                    getLog().error( "artifact download url: " + artifact.getDownloadUrl() );
                    getLog().error( "artifact repository: " + artifact.getRepository() );
                    getLog().error( "artifact repository: " + artifact.getVersion() );
                    throw new IllegalStateException(
                        "artifact " + artifact + " has no matching file, why? Check the logs..." );
                }

                String outputName = getArtifactJnlpName(artifact);

                File targetDirectory = getArtifactJnlpDirFile(artifact); 

                if(jnlp.getCopyJars()) {
                	boolean copied = copyFileToDirectoryIfNecessary( toCopy, targetDirectory, 
                			outputName );

                	if ( copied )
                	{
                		this.copiedJnlpArtifacts.add(new File(targetDirectory, outputName));
                	}
                }
                	
                artifactList.add( artifact );
                
                if ( artifactContainsClass( artifact, jnlp.getMainClass() ) )
                {
                    if ( artifactWithMainClass == null )
                    {
                        artifactWithMainClass = artifact;
                        getLog().debug( "Found main jar. Artifact " + artifactWithMainClass +
                            " contains the main class: " + jnlp.getMainClass() );
                    }
                    else
                    {
                        getLog().warn( "artifact " + artifact + " also contains the main class: " +
                            jnlp.getMainClass() + ". IGNORED." );
                    }
                }
            }
            else
            // FIXME how do we deal with native libs?
            // we should probably identify them and package inside jars that we timestamp like the native lib
            // to avoid repackaging every time. What are the types of the native libs?
            {
                getLog().debug( "Skipping artifact of type " + type + " for " + getWorkDirectory().getName() );
            }
            // END COPY
        }
    }

    public final static String DEFAULT_ARTIFACT_GROUP = "default";
    
    protected void buildArtifactGroups()
    {
    	if(artifactGroups == null || artifactGroups.size() <= 0) {
    		// There are no artifactGroups
    		// TODO we should put all the artifacts into the main group
    		jnlpArtifactGroups.put(DEFAULT_ARTIFACT_GROUP, packagedJnlpArtifacts);
    		return;
    	}
    	
    	
    	for(Iterator i = artifactGroups.iterator(); i.hasNext(); ) {    		
    		ArtifactGroup group = (ArtifactGroup)i.next();
    		
    		ArtifactFilter filter = createArtifactFilter(group);

    		List groupArtifacts = new ArrayList();
    		jnlpArtifactGroups.put(group.getName(), groupArtifacts);
    		
    		for(Iterator j = packagedJnlpArtifacts.iterator(); j.hasNext(); ) {
    			Artifact artifact = (Artifact)j.next();
    			if(filter.include(artifact)){
    				groupArtifacts.add(artifact);
    			}
    		}
    	}
    }
    
    public String getArtifactJnlpVersion(Artifact artifact)
    {
    	// FIXME this should convert the version so it is jnlp safe
    	// FIXME this isn't correctly resovling some artifacts to their
    	// actual version numbers.  This appears to happen based on the
    	// metadata files stored in the local repository.  I wasn't able
    	// narrow down exactly what was going on, but I think it is due
    	// to running the jnlp goal without the -U (update snapshots) 
    	// if a snapshot is built locally then its metadata is in a different
    	// state than if it is downloaded from a remote repository.
    	return  artifact.getVersion();   	
    }

    /**
     * This returns the base file name (without the .jar) of the artifact 
     * as it should appear in the href attribute of a jar or nativelib element in the
     * jnlp file.  This is also the name of the file that should appear before
     * __V if the file is hosted on then jnlp-servlet. 
     * If fileVersions is enabled then this name will not include the version.
     * 
     * @param artifact
     * @return
     */
    public String getArtifactJnlpBaseName(Artifact artifact)
    {
    	String baseName = artifact.getArtifactId();
        if(jnlp == null || !jnlp.getFileVersions()){
        	baseName += "-" + artifact.getVersion();
        }

        if(artifact.hasClassifier()){
    		baseName += "-" + artifact.getClassifier();
    	}
        
        // Because all the files need to end in jar for jnlp to handle them
        // non jar files have there type appended to them so they don't conflict
        // if there are two artifacts that only differ by their type.
        if(!("jar".equals(artifact.getType()))){
        	baseName += "-" + artifact.getType();
        }
        
        return baseName;
    }
    
    /**
     * This returns the file name of the artifact as it should
     * appear in the href attribute of a jar or nativelib element in the
     * jnlp file.  If fileVersions is enabled then this name will not include
     * the version.
     * It will always end in jar even if the type of the artifact isn't a jar.
     * 
     * @param artifact
     * @return
     */
    public String getArtifactJnlpHref(Artifact artifact)
    {
    	return getArtifactJnlpBaseName(artifact) + ".jar";
    }
    
    public String getArtifactJnlpName(Artifact artifact)
    {
    	String jnlpName = getArtifactJnlpBaseName(artifact);
        if(jnlp != null && jnlp.getFileVersions()){
        	// FIXME this should convert the version so it is jnlp safe
        	jnlpName += "__V" + getArtifactJnlpVersion(artifact);
        } 
        
        return jnlpName + ".jar";
    }
    
    public String getArtifactJnlpDir(Artifact artifact)
    {
    	if(jnlp != null && jnlp.getGroupIdDirs()){
    		String groupPath = artifact.getGroupId().replace('.', '/');
    		return groupPath + "/" + artifact.getArtifactId() + "/";
        } else {
        	return "";
        }    	
    }
    
    public File getArtifactJnlpDirFile(Artifact artifact)
    {
        File targetDirectory = 
        	new File(getWorkDirectory(), getArtifactJnlpDir(artifact));
        
        targetDirectory.mkdirs();

        return targetDirectory;
    }
    
    private boolean artifactContainsClass( Artifact artifact, final String mainClass )
        throws MalformedURLException
    {
        boolean containsClass = true;

        // JarArchiver.grabFilesAndDirs()
        ClassLoader cl = new java.net.URLClassLoader( new URL[]{artifact.getFile().toURL()} );
        Class c = null;
        try
        {
            c = Class.forName( mainClass, false, cl );
        }
        catch ( ClassNotFoundException e )
        {
            getLog().debug( "artifact " + artifact + " doesn't contain the main class: " + mainClass );
            containsClass = false;
        }
        catch ( Throwable t )
        {
            getLog().info( "artifact " + artifact + " seems to contain the main class: " + mainClass +
                " but the jar doesn't seem to contain all dependencies " + t.getMessage() );
        }

        if ( c != null )
        {
            getLog().debug( "Checking if the loaded class contains a main method." );

            try
            {
                c.getMethod( "main", new Class[]{String[].class} );
            }
            catch ( NoSuchMethodException e )
            {
                getLog().warn( "The specified main class (" + mainClass +
                    ") doesn't seem to contain a main method... Please check your configuration." + e.getMessage() );
            }
            catch ( NoClassDefFoundError e )
            {
                // undocumented in SDK 5.0. is this due to the ClassLoader lazy loading the Method thus making this a case tackled by the JVM Spec (Ref 5.3.5)!
                // Reported as Incident 633981 to Sun just in case ...
                getLog().warn( "Something failed while checking if the main class contains the main() method. " +
                    "This is probably due to the limited classpath we have provided to the class loader. " +
                    "The specified main class (" + mainClass +
                    ") found in the jar is *assumed* to contain a main method... " + e.getMessage() );
            }
            catch ( Throwable t )
            {
                getLog().error( "Unknown error: Couldn't check if the main class has a main method. " +
                    "The specified main class (" + mainClass +
                    ") found in the jar is *assumed* to contain a main method...", t );
            }
        }

        return containsClass;
    }

    void generateJnlpFile( File outputDirectory )
        throws MojoExecutionException
    {
        if ( jnlp.getOutputFile() == null || jnlp.getOutputFile().length() == 0 )
        {
            getLog().debug( "Jnlp output file name not specified. Using default output file name: launch.jnlp." );
            jnlp.setOutputFile( "launch.jnlp" );
        }
        File jnlpOutputFile = new File( outputDirectory, jnlp.getOutputFile() );

        if ( jnlp.getInputTemplate() == null || jnlp.getInputTemplate().length() == 0 )
        {
            getLog().debug(
                "Jnlp template file name not specified. Using default output file name: src/jnlp/template.vm." );
            jnlp.setInputTemplate( "src/jnlp/template.vm" );
        }
        String templateFileName = jnlp.getInputTemplate();

        File resourceLoaderPath = getProject().getBasedir();

        if ( jnlp.getInputTemplateResourcePath() != null && jnlp.getInputTemplateResourcePath().length() > 0 )
        {
            resourceLoaderPath = new File( jnlp.getInputTemplateResourcePath() );
        }

        Generator jnlpGenerator = 
        	new Generator( this, resourceLoaderPath, jnlpOutputFile, 
        			templateFileName );
        try
        {
            jnlpGenerator.generate();
        }
        catch ( Exception e )
        {
            getLog().debug( e .toString() );
            throw new MojoExecutionException( "Could not generate the JNLP deployment descriptor", e );
        }

        // optionally copy the outputfile to one with the version string appended
        if(jnlp.getMakeJnlpWithVersion() &&
        		hasJnlpChanged(outputDirectory, jnlpOutputFile)){
        	
        	try {
        		String versionedJnlpName =  getVersionedArtifactName() + ".jnlp";
        		File versionedJnlpOutputFile = new File( outputDirectory, versionedJnlpName );
        		FileUtils.copyFile(jnlpOutputFile, versionedJnlpOutputFile);
        		
        		writeLastJnlpVersion(outputDirectory, getJnlpBuildVersion());
			} catch (IOException e) {
				e.printStackTrace();
			}        	
        }
    }

    public File getLastVersionFile(File outputDirectory)
    {
    	String artifactId = getProject().getArtifactId();
    	return new File(outputDirectory, 
    			artifactId + "-CURRENT_VERSION.txt");
    }
    
    /**
     * If there is no version file then this returns null
     * @return
     */
    public String readLastJnlpVersion(File outputDirectory)
    {
    	File currentVersionFile = getLastVersionFile(outputDirectory); 
    		
    	// check if this file has changed from the old version
		if(!currentVersionFile.exists()){
			return null; 
		}

		try {
			String oldVersion = FileUtils.fileRead(currentVersionFile);
			return oldVersion.trim();
		} catch (IOException e) {
			e.printStackTrace();			
		}

		return null;
    }
    
    public void writeLastJnlpVersion(File outputDirectory, String version)
    {
    	File currentVersionFile = getLastVersionFile(outputDirectory); 
		
    	try {
			FileUtils.fileWrite(currentVersionFile.getAbsolutePath(), version);
		} catch (IOException e) {
			e.printStackTrace();
		}    	
    }
    
    public boolean hasJnlpChanged(File outputDirectory, File newJnlpFile)
    {
    	String artifactId = getProject().getArtifactId();

		String oldVersion = readLastJnlpVersion(outputDirectory);

		if(oldVersion == null) {
			return true;
		}

		// look for the old version
		String oldVersionedJnlpName = 
			artifactId + "-" + oldVersion + ".jnlp";
		File oldVersionedJnlpFile = 
			new File(outputDirectory, oldVersionedJnlpName);
			
		if(!oldVersionedJnlpFile.exists()) {
			return true;
		}

		try {
			String oldJnlpText = FileUtils.fileRead(oldVersionedJnlpFile);
			String newJnlpText = FileUtils.fileRead(newJnlpFile);

			// If the strings match then there is no new version
			if(oldJnlpText.equals(newJnlpText)){
				return false;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return true;

    }
    
    public String getJnlpBuildVersion()
    {
    	if(jnlpBuildVersion != null) {
    		return jnlpBuildVersion;
    	}

    	DateFormat utcDateFormatter = new SimpleDateFormat( UTC_TIMESTAMP_PATTERN );
    	utcDateFormatter.setTimeZone( UTC_TIME_ZONE );
    	String timestamp = utcDateFormatter.format( new Date() );

    	String version = getProject().getVersion();
    	if(version.endsWith("SNAPSHOT")){
    		version = version.replaceAll("SNAPSHOT", timestamp);
    	}
    	
    	jnlpBuildVersion = version;
    	return version;
    }
    
    private void logCollection( final String prefix, final Collection collection )
    {
        getLog().debug( prefix + " " + collection );
        if ( collection == null )
        {
            return;
        }
        for ( Iterator it3 = collection.iterator(); it3.hasNext(); )
        {
            getLog().debug( prefix + it3.next() );
        }
    }

    private void deleteKeyStore()
    {
        File keyStore = null;
        if ( sign.getKeystore() != null )
        {
            keyStore = new File( sign.getKeystore() );
        }
        else
        {
            // FIXME decide if we really want this.
            // keyStore = new File( System.getProperty( "user.home") + File.separator + ".keystore" );
        }

        if ( keyStore == null )
        {
            return;
        }
        if ( keyStore.exists() )
        {
            if ( keyStore.delete() )
            {
                getLog().debug( "deleted keystore from: " + keyStore.getAbsolutePath() );
            }
            else
            {
                getLog().warn( "Couldn't delete keystore from: " + keyStore.getAbsolutePath() );
            }
        }
        else
        {
            getLog().debug( "Skipping deletion of non existing keystore: " + keyStore.getAbsolutePath() );
        }
    }

    private void genKeyStore()
        throws MojoExecutionException
    {
        GenkeyMojo genKeystore = new GenkeyMojo();
        genKeystore.setAlias( sign.getAlias() );
        genKeystore.setDname( sign.getDname() );
        genKeystore.setKeyalg( sign.getKeyalg() );
        genKeystore.setKeypass( sign.getKeypass() );
        genKeystore.setKeysize( sign.getKeysize() );
        genKeystore.setKeystore( sign.getKeystore() );
        genKeystore.setSigalg( sign.getSigalg() );
        genKeystore.setStorepass( sign.getStorepass() );
        genKeystore.setStoretype( sign.getStoretype() );
        genKeystore.setValidity( sign.getValidity() );
        genKeystore.setVerbose( this.verbose );
        genKeystore.setWorkingDir( getWorkDirectory() );

        genKeystore.execute();
    }

    private File getWorkDirectory()
    {
        return workDirectory;
    }

    /**
     * Conditionaly copy the file into the target directory.
     * The operation is not performed when the target file exists is up to date.
     * The target file name is taken from the <code>sourceFile</code> name.
     *
     * @return <code>true</code> when the file was copied, <code>false</code> otherwise.
     * @throws NullPointerException is sourceFile is <code>null</code> or
     *                              <code>sourceFile.getName()</code> is <code>null</code>
     * @throws IOException          if the copy operation is tempted but failed.
     */
    private boolean copyFileToDirectoryIfNecessary( File sourceFile, File targetDirectory,
    		String outputName)
        throws IOException
    {

        if ( sourceFile == null )
        {
            throw new NullPointerException( "sourceFile is null" );
        }

        File targetFile = new File( targetDirectory, outputName );

        boolean shouldCopy = ! targetFile.exists() || targetFile.lastModified() < sourceFile.lastModified();

        if ( shouldCopy )
        {

            FileUtils.copyFile( sourceFile, targetFile);

        }
        else
        {

            getLog().debug(
                "Source file hasn't changed. Do not overwrite " + targetFile + " with " + sourceFile + "." );

        }
        return shouldCopy;
    }

    /**
     * return the number of signed jars *
     */
    private int signJars( File directory, FileFilter fileFilter )
        throws MojoExecutionException
    {
        File[] jarFiles = directory.listFiles( fileFilter );

        getLog().debug( "signJars in " + directory + " found " + jarFiles.length + " jar(s) to sign" );

        return signJars(directory, jarFiles);
    }
    
    /**
     * return the number of signed jars *
     */
    private int signJars( File directory, File [] jarFiles )
        throws MojoExecutionException
    {

        if ( jarFiles.length == 0 )
        {
            return 0;
        }

        JarSignMojo signJar = new JarSignMojo();
        signJar.setAlias( sign.getAlias() );
        signJar.setBasedir( basedir );
        signJar.setKeypass( sign.getKeypass() );
        signJar.setKeystore( sign.getKeystore() );
        signJar.setSigFile( sign.getSigfile() );
        signJar.setStorepass( sign.getStorepass() );
        signJar.setType( sign.getStoretype() );
        signJar.setVerbose( this.verbose );
        signJar.setWorkingDir( getWorkDirectory() );
        signJar.setVerify( sign.getVerify() );
                
        for ( int i = 0; i < jarFiles.length; i++ )
        {
            signJar.setJarPath( jarFiles[i] );

            File signedJar = null;
            if(sign.getForce()) {
            	// jars should be signed even if they are already
            	// signed.  So set the signed jar to be a temporary
            	// file and then delete the original an move this one in
            	signedJar = new File(jarFiles[i].getParentFile(), 
            			jarFiles[i].getName() + ".signed");
            	
            } 
            
            // If the signedJar is set to null then the jar is signed
            // in place.
            signJar.setSignedJar(signedJar);

            long lastModified = jarFiles[i].lastModified();
            signJar.execute();
            
            if(signedJar != null) {
            	jarFiles[i].delete();
            	signedJar.renameTo(jarFiles[i]);
            }
            
            jarFiles[i].setLastModified( lastModified );
        }

        return jarFiles.length;
    }

    private void checkInput()
        throws MojoExecutionException
    {

        getLog().debug( "a fact " + this.artifactFactory );
        getLog().debug( "a resol " + this.artifactResolver );
        getLog().debug( "basedir " + this.basedir );
        getLog().debug( "gzip " + this.gzip );
        getLog().debug( "pack200 " + this.pack200 );
        getLog().debug( "project " + this.getProject() );
        getLog().debug( "zipArchiver " + this.zipArchiver );
        // getLog().debug( "usejnlpservlet " + this.usejnlpservlet );
        getLog().debug( "verifyjar " + this.verifyjar );
        getLog().debug( "verbose " + this.verbose );

        if ( jnlp == null )
        {
            // throw new MojoExecutionException( "<jnlp> configuration element missing." );
        }

        if ( SystemUtils.JAVA_VERSION_FLOAT < 1.5f )
        {
            if ( pack200 )
            {
                throw new MojoExecutionException( "SDK 5.0 minimum when using pack200." );
            }
        }

        // FIXME
        /*
        if ( !"pom".equals( getProject().getPackaging() ) ) {
            throw new MojoExecutionException( "'" + getProject().getPackaging() + "' packaging unsupported. Use 'pom'" );
        }
        */
    }

    /**
     * @return
     */
    public MavenProject getProject()
    {
        return project;
    }

    void setWorkDirectory( File workDirectory )
    {
        this.workDirectory = workDirectory;
    }

    void setVerbose( boolean verbose )
    {
        this.verbose = verbose;
    }

    public JnlpConfig getJnlp()
    {
        return jnlp;
    }

    public List getPackagedJnlpArtifacts()
    {
        return packagedJnlpArtifacts;
    }

    public Map getJnlpArtifactGroups()
    {
    	return jnlpArtifactGroups;
    }
    
    /*
    public Artifact getArtifactWithMainClass() {
        return artifactWithMainClass;
    }
    */

    public boolean isArtifactWithMainClass( Artifact artifact )
    {
        final boolean b = artifactWithMainClass.equals( artifact );
        getLog().debug( "compare " + artifactWithMainClass + " with " + artifact + ": " + b );
        return b;
    }

    public String getSpec()
    {
        // shouldn't we automatically identify the spec based on the features used in the spec?
        // also detect conflicts. If user specified 1.0 but uses a 1.5 feature we should fail in checkInput().
        if ( jnlp.getSpec() != null )
        {
            return jnlp.getSpec();
        }
        return "1.0+";
    }

	/**
	 * @return
	 */
	public String getVersionedArtifactName() {
		return getProject().getArtifactId() + "-" + getJnlpBuildVersion();
	}
}

