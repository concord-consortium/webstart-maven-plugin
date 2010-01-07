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
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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
import org.apache.maven.plugin.jar.JarSignVerifyMojo;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.settings.Settings;
import org.apache.tools.ant.BuildException;
import org.codehaus.mojo.keytool.GenkeyMojo;
import org.codehaus.mojo.webstart.generator.Generator;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;

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
    
    private ArrayList processedJnlpArtifacts = new ArrayList();
    
    private Artifact artifactWithMainClass;

    // initialized by execute
    private long startTime;

	private String jnlpBuildVersion;

	private File temporaryDirectory;

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
     * @throws MojoExecutionException 
     */
    private void processDependencies()
        throws IOException, MojoExecutionException
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
        
        // Check if the temporaryDirectory is empty, if so delete it
        File tmpDirectory = getTemporaryDirectory();
        File [] tmpArtifactDirs = tmpDirectory.listFiles();
        if(tmpArtifactDirs != null && tmpArtifactDirs.length != 0){
        	throw new MojoExecutionException("Left over files in : " + tmpDirectory);        	
        }
        
        tmpDirectory.delete();
    }

    private void processDependency( Artifact artifact , List artifactList)
        throws IOException, MojoExecutionException
    {
        // TODO: scope handler
    	// skip provided and test scopes
        if ( Artifact.SCOPE_PROVIDED.equals( artifact.getScope() ) ||
            Artifact.SCOPE_TEST.equals( artifact.getScope() ) )
        {
        	return;
        }
        
        String type = artifact.getType();
        
        // skip artifacts that are not jar or nar
        // nar is how we handle native libraries 
        if ( !("jar".equals( type )) && !("nar".equals( type )))
        {
        	getLog().debug( "Skipping artifact of type " + type + " for " + 
        			getWorkDirectory().getName() );
        	return;
        }
        
        
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

        // check if this artifact has the main class in it
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

        // Add the artifact to the list even if it is not processed
        artifactList.add( artifact );

        String outputName = getArtifactJnlpName(artifact);

        File targetDirectory = getArtifactJnlpDirFile(artifact); 

        // Check if this file needs to be updated in the targetDirectory
        // currently this check is just based on the existance of a jar and if the 
        // modified time of the jar in the maven cache is newer or older than 
        // that in the targetDirectory.  It would be better to use the version of the 
        // jar  
        if(!needsUpdating(toCopy, targetDirectory, outputName)){
        	getLog().debug( "skipping " + artifact + " it has already been processed");
        	return;
        }

        // Instead of copying the file to its final location we make a temporary
        // folder and put the jar there.  This way if something fails we won't
        // leave bad files in the final output folder
        File tmpArtifactDirectory = getArtifactTemporaryDirFile(artifact);
        File currentJar = new File(tmpArtifactDirectory, outputName);
        
        FileUtils.copyFile( toCopy, currentJar);

        //
        // pack200 and jar signing
        //
        
        // This used to be conditional based on a sign config and 
        // a pack boolean.  We now just try to do these things all the time
        
        // there used to be some automatic keystore generation here but
        // we aren't using it anymore

        // http://java.sun.com/j2se/1.5.0/docs/guide/deployment/deployment-guide/pack200.html
        // we need to pack then unpack the files before signing them

        File packedJar = new File(currentJar.getAbsolutePath() + ".pack");

        // There is no need to gz them if we are just going to uncompress them
        // again. (gz isn't losy like pack is)
        // We should handle the case where a jar cannot be packed.
        String shortName = getArtifactFlatPath(artifact) + "/" + outputName;
        
        getLog().info("processing: " + shortName);        

        boolean doPack200 = true;
        
        // We should remove any previous signature information.  Signatures on the file
        // mess up verification of the signature, because there ends up being 2 signatures
        // in the jar.  The pack code does a signature verification before packing so 
        // to be safe we want to remove any signatures before we do any packing.
        removeSignatures(currentJar, shortName);
        
        getLog().debug("packing : " + shortName);        
        try {
        	Pack200.packJar( currentJar, false );
        } catch (BuildException e){
        	// it will throw an ant.BuildException if it can't pack the jar
        	// One example is with
        	//  <groupId>com.ibm.icu</groupId>
        	//  <artifactId>icu4j</artifactId>
        	//  <version>2.6.1</version>
        	// That jar has some class that causes the packing code to die trying
        	// to read it in.
        	
        	// Another time it will not be able to pack the jar if it has an invalid
        	// signature.  That one we can fix by removing the signature
        	getLog().warn("Cannot pack: " + artifact, e);
        	doPack200 = false;
        	
        	// It might have left a bad pack jar
        	if(packedJar.exists()){
        		packedJar.delete();
        	}        	
        }

        if(!doPack200){
        	// Packing is being skipped for some reason so we need to sign 
        	// and verify it separately
        	signJar(currentJar, shortName);
        	
        	if(!verifyJar(currentJar, shortName)){
        		// We cannot verify this jar
        		throw new MojoExecutionException("failed to verify signed jar: " + shortName);
        	}        	
        } else {

        	getLog().debug("unpacking : " + shortName + ".pack");
        	Pack200.unpackJar( packedJar );

        	// specs says that one should do it twice when there are unsigned jars??
        	// I don't know about the unsigned part, but I found a jar
        	// that had to be packed, unpacked, packed, and unpacked before
        	// it could be signed and packed correctly.
        	// I suppose the best way to do this would be to try the signature
        	// and if it fails then pack it again, instead of packing and unpack
        	// every single jar
        	boolean verified = false;
        	for(int i=0; i<2; i++){
        		// This might throw a mojo exception if the signature didn't
        		// verify.  This might happen if the jar has some previous
        		// signature information
        		signJar(currentJar, shortName );

        		// Now we pack and unpack the jar
        		getLog().debug("packing : " + shortName);
        		Pack200.packJar( currentJar, false );

        		getLog().debug("unpacking : " + shortName + ".pack");
        		Pack200.unpackJar( packedJar );

        		// Check if the jar is signed correctly
        		if(verifyJar(currentJar, shortName)){
        			verified = true;
        			break;
        		}

        		// verfication failed here
        		getLog().info("verfication failed, attempt: " + i);
        	}

        	if(!verified){
        		throw new MojoExecutionException("Failed to verify sigature after signing, " +
        		"packing, and unpacking multiple times");
        	}

        	// Now we need to gzip the resulting packed jar.
        	getLog().debug("gzipping: " + shortName + ".pack");
        	FileInputStream inStream = new FileInputStream(packedJar);
        	FileOutputStream outFileStream = 
        		new FileOutputStream(packedJar.getAbsolutePath() + ".gz");
        	GZIPOutputStream outGzStream = new GZIPOutputStream(outFileStream);
        	IOUtil.copy(inStream, outGzStream);
        	outGzStream.close();
        	outFileStream.close();
        	
        	// delete the packed jar because we only need the gz jar
        	packedJar.delete();
        }
        
        // If we are here then it is assumed the jar has been signed, packed and verified
        
        // We need to rename all the files in the temporaryDirectory so they 
        // go to the targetDirectory
        File [] tmpFiles = tmpArtifactDirectory.listFiles();
        for(int i=0; i<tmpFiles.length; i++){
        	File targetFile = new File(targetDirectory, tmpFiles[i].getName());
        	// This is better than the File.renameTo because it will through
        	// and exception if something goes wrong
        	FileUtils.rename(tmpFiles[i], targetFile);
        }

        tmpFiles = tmpArtifactDirectory.listFiles();
        if(tmpFiles != null && tmpFiles.length != 0){
        	throw new MojoExecutionException("Could not move files out of: " + 
        			tmpArtifactDirectory);
        }
        
        tmpArtifactDirectory.delete();
        
        getLog().debug("moved files to: " + targetDirectory);
        
        // make the snapshot copies if necessary
        if(jnlp.getMakeSnapshotsWithNoJNLPVersion() && artifact.isSnapshot()) {
        	String jarBaseName = getArtifactJnlpBaseName(artifact);

        	String snapshot_outputName = jarBaseName + "-" + 
        		artifact.getBaseVersion() + ".jar";

        	File versionedFile = 
        		new File(targetDirectory, getArtifactJnlpName(artifact));

        	// this is method should reduce the number of times a file 
        	// needs to be downloaded by an applet or webstart.  However
        	// it isn't very safe if multiple users are running this. 
        	// this method will be comparing the date of the file just 
        	// setup in the jnlp folder with the last generated snapshot
        	copyFileToDirectoryIfNecessary( versionedFile, targetDirectory, 
        			snapshot_outputName );
        }
        
        // Record that this artifact was successfully processed.
        // this might not be necessary in the future
        this.processedJnlpArtifacts.add(new File(targetDirectory, outputName));
    }

    
    protected void removeSignatures(File currentJar, String shortName)
    	throws IOException
    {
        // We should remove any previous signature information.  This
        // can screw up the packing code, and if it is signed with 2 signatures
        // it can not be verified.
        ZipFile currentJarZipFile = new ZipFile(currentJar);
        Enumeration entries = currentJarZipFile.entries();
        
        // There might be more valid extensions but this is what we've seen so far 
        // the ?i makes it case insensitive       
        Pattern signatureChecker = 
        	Pattern.compile("(?i)^meta-inf/.*((\\.sf)|(\\.rsa))$");

        getLog().debug("checking for old signature : " + shortName);                
        Vector signatureFiles = new Vector();
        while(entries.hasMoreElements()){
        	ZipEntry entry = (ZipEntry)entries.nextElement();
        	Matcher matcher = signatureChecker.matcher(entry.getName());
        	if(matcher.find()){
        		// we found a old signature in the file
        		signatureFiles.add(entry.getName());
                getLog().warn("found signature: " + entry.getName());                		
        	}
        }

        if(signatureFiles.size() == 0){
        	// no old files were found
        	currentJarZipFile.close();
        	return;
        }

        // We found some old signature files, write out the file again without
        // them    
        File outFile = new File(currentJar.getParent(), currentJar.getName() + ".unsigned");
        ZipOutputStream zipOutStream = new ZipOutputStream(new FileOutputStream(outFile));
        
        entries = currentJarZipFile.entries();
        while(entries.hasMoreElements()){
        	ZipEntry entry = (ZipEntry)entries.nextElement();
        	if(signatureFiles.contains(entry.getName())){
        		continue;
        	}
        	InputStream entryInStream = currentJarZipFile.getInputStream(entry);
        	ZipEntry newEntry = new ZipEntry(entry);
        	zipOutStream.putNextEntry(newEntry);
        	IOUtil.copy(entryInStream, zipOutStream);
        	entryInStream.close();
        }

        zipOutStream.close();
        currentJarZipFile.close();
        
        FileUtils.rename(outFile, currentJar);
        
        getLog().info("removed signature");
    }
    
    protected boolean verifyJar(File jar, String shortName)
    	throws MojoExecutionException
    {
    	getLog().debug("verifying: " + shortName);
    	JarSignVerifyMojo verifier = new JarSignVerifyMojo();
    	verifier.setJarPath(jar);
    	verifier.setLog(getLog());
    	verifier.setErrorWhenNotSigned(false);
    	verifier.setWorkingDir(jar.getParentFile());
    	
    	try{
    		verifier.execute();
    	} catch (MojoExecutionException e){
    		// We failed to verify the jar.  Even though the method 
    		// errorWhenNotSigned is set to false an error is still though in some cases
    		// we are throwing out the exception here because the best error message has
    		// already been logged to info by the jarsigner command itself.
    		// the message in the exception simply says the command returned something
    		// other than zero.
    		return false;
    	}
    	
    	return verifier.isSigned();
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
    	String version =  artifact.getVersion();
    	
    	if(version.contains("-SNAPSHOT")){
    		File artifactFile = artifact.getFile();
        	DateFormat utcDateFormatter = new SimpleDateFormat( UTC_TIMESTAMP_PATTERN );
        	utcDateFormatter.setTimeZone( UTC_TIME_ZONE );
        	String timestamp = utcDateFormatter.format( new Date(artifactFile.lastModified()) );
        	
        	version = version.replaceAll("SNAPSHOT", timestamp);
        	
        	getLog().debug("constructing local timestamp: " + timestamp + 
        			" for SNAPSHOT: " + artifact);
    	}
    	
    	String suffix = jnlp.getVersionSuffix(); 
    	if(suffix != null){
    		version += suffix;
    	}
    	return version;
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
    
    public File getArtifactTemporaryDirFile(Artifact artifact)
    {
		String artifactFlatPath = getArtifactFlatPath(artifact);

		File tmpDir = new File(getTemporaryDirectory(), artifactFlatPath);	
		tmpDir.mkdirs();
		return 	tmpDir;
    }
    
    public String getArtifactFlatPath(Artifact artifact)
    {
		return artifact.getGroupId() + "." + artifact.getArtifactId();
    }
    
    /**
     * this is created once per execution I'm assuming a new instance of this
     * class is created for each execution
     * @return
     */
    public File getTemporaryDirectory()
    {
    	if(temporaryDirectory != null){
    		return temporaryDirectory;
    	}

    	temporaryDirectory = new File(getWorkDirectory(), "tmp/" +
    			getVersionedArtifactName());
    	temporaryDirectory.mkdirs();
    	
    	return temporaryDirectory;
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
        
        // decide if this new jnlp is actually different from the old
        // jnlp file. 
        if(!hasJnlpChanged(outputDirectory, jnlpGenerator)){
        	return;
        }

        try
        {
        	// this writes out the file
        	jnlpGenerator.generate();
        }
        catch ( Exception e )
        {
        	getLog().debug( e .toString() );
        	throw new MojoExecutionException( "Could not generate the JNLP deployment descriptor", e );
        }

        // optionally copy the outputfile to one with the version string appended
        // use the generator to write out a copy of the file, use its new name
        if(jnlp.getMakeJnlpWithVersion()){
        	try {
        		String versionedJnlpName =  getVersionedArtifactName() + ".jnlp";            		
        		File versionedJnlpOutputFile = 
        			new File( outputDirectory, versionedJnlpName );
        		FileWriter versionedJnlpWriter = 
        			new FileWriter(versionedJnlpOutputFile);
        		jnlpGenerator.generate(versionedJnlpWriter, 
        				versionedJnlpName, getVersionedArtifactName());

        		writeLastJnlpVersion(outputDirectory, getJnlpBuildVersion());
        	} catch (Exception e) {
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
    
    /**
     * This method uses the generator because of the timestamp that can be
     * included in the file.  To do the comparison it uses the current 
     * dependencies and system properties, but it uses the old file name
     * and old versionedArtifactName
     * 
     * @param outputDirectory
     * @param generator
     * @return
     */
    public boolean hasJnlpChanged(File outputDirectory, Generator generator)
    {
    	String artifactId = getProject().getArtifactId();

		String oldVersion = readLastJnlpVersion(outputDirectory);

		if(oldVersion == null) {
			return true;
		}

		// look for the old version
		String oldVersionedArtifactName = artifactId + "-" + oldVersion;
		String oldVersionedJnlpName = oldVersionedArtifactName + ".jnlp";
		File oldVersionedJnlpFile = 
			new File(outputDirectory, oldVersionedJnlpName);
			
		if(!oldVersionedJnlpFile.exists()) {
			return true;
		}

		try {
			String oldJnlpText = FileUtils.fileRead(oldVersionedJnlpFile);
			StringWriter updatedJnlpWriter = new StringWriter();
			generator.generate(updatedJnlpWriter, 
					oldVersionedJnlpName, oldVersionedArtifactName);
			String updatedJnlpText = updatedJnlpWriter.toString();
			
			// If the strings match then there is no new version
			if(oldJnlpText.equals(updatedJnlpText)){
				return false;
			}
		} catch (Exception e) {
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

        File targetFile = new File( targetDirectory, outputName );

        boolean shouldCopy = needsUpdating(sourceFile, targetDirectory, outputName); 

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

    private boolean needsUpdating(File sourceFile, File targetDirectory,
    		String outputName)
    {
        if ( sourceFile == null )
        {
            throw new NullPointerException( "sourceFile is null" );
        }

        File targetFile = new File( targetDirectory, outputName );

        if(outputName.contains("__V")){
        	// never update a versioned file that already exists
        	// if a webstart client has downloaded this versioned file it will expect the server to have the same one
        	// as before.
        	return ! targetFile.exists();
        } else {
            return ! targetFile.exists() || targetFile.lastModified() < sourceFile.lastModified();        	
        }
    }
        
    /**
     * 
     * @param relativeName 
     * @throws IOException 
     */
    private void signJar(File jarFile, String relativeName )
        throws MojoExecutionException, IOException
    {
    	getLog().debug("signing: " + relativeName);
    	
    	JarSignMojo signJar = new JarSignMojo();
    	signJar.setSkipAttachSignedArtifact(true);
    	signJar.setLog(getLog());
    	signJar.setAlias( sign.getAlias() );
    	signJar.setBasedir( basedir );
    	signJar.setKeypass( sign.getKeypass() );
    	signJar.setKeystore( sign.getKeystore() );
    	signJar.setSigFile( sign.getSigfile() );
    	signJar.setStorepass( sign.getStorepass() );
    	signJar.setType( sign.getStoretype() );
    	signJar.setVerbose( this.verbose );
    	signJar.setWorkingDir( getWorkDirectory() );
    	
    	// we do our own verification because the jarsignmojo doesn't pass
    	// the log object, to the jarsignverifymojo, so lot 
    	signJar.setVerify( false );

    	signJar.setJarPath( jarFile );

    	File signedJar = new File(jarFile.getParentFile(), 
    				jarFile.getName() + ".signed");

    	// If the signedJar is set to null then the jar is signed
    	// in place.
    	signJar.setSignedJar(signedJar);

    	long lastModified = jarFile.lastModified();
    	signJar.execute();

    	FileUtils.rename(signedJar, jarFile);

    	jarFile.setLastModified( lastModified );
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

