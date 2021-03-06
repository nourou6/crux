/*
 * Copyright (c) 2016. University Corporation for Atmospheric Research (UCAR). All rights reserved.
 */

package edu.ucar.ral.crux;

import ch.qos.logback.classic.Level;
import org.apache.tools.ant.DirectoryScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The main class for Crux, which allows for validating XML and XSD files against their XML Schema, as well as against
 * Schematron definitions. This class can be used from the command-line or directly via methods
 */
public class Crux {
  private static final Logger LOG = LoggerFactory.getLogger( Crux.class ); 
  
  private SchematronValidator schematronValidator = new SchematronValidator();
  private boolean allowingRemoteResources = false;

  /**
   * Validate any number of XML or XSD files against their XML Schema and optionally against a local Schematron definition.  
   * Local XML/XSD paths may include wildcards such as "*" or "?" 
   * @param catalogFile the path to a local catalog file.  May be null
   * @param schematronFile the path to a local Schematron (.sch) definition.  May be null
   * @param xmlOrXsdPaths a set of file paths to XML or XSD files.  These may be local file paths or remote http: paths
   * @return the number of files which were validated
   * @throws ValidationException if validation failures occur
   * @throws IOException if necessary files could not be read
   * @throws SAXException if the XML to validate is not well-structured
   * @throws ParserConfigurationException if a parser configuration error occurs
   */
  public int validate( String catalogFile, String schematronFile, String... xmlOrXsdPaths ) throws ValidationException, IOException, SAXException, ParserConfigurationException {
    XML10Validator validator;
    if( catalogFile == null ) {
      validator = new XML10Validator();
    } else {
      validator = new XML10Validator( catalogFile );
    }

    if( !isAllowingRemoteResources() ) {
      LOG.info( "Offline mode enabled, schema resolution will only use local files" );
    }
    validator.setAllowingRemoteResources( isAllowingRemoteResources() );

    List<ValidationError> errors = new ArrayList<>();
    int numFilesValidated = 0;

    for( String filePath : xmlOrXsdPaths ) {
      boolean isLocal = Utils.isLocalFile( filePath );
      try {
        //if we have a local file we resolve wildcards
        if( isLocal ) {
          if( filePath.contains( ".." ) ){
            throw new IOException( "Relative paths using '..' are not currently supported" );
          }
          //a full relative path library is not readily available for Java - Ant DirectoryScanner doesn't seem to handle
          //paths with intermixed "." and "..". To fix the "." issues we trim off "./"
          while( filePath.startsWith( "./" ) ) {
            filePath = filePath.substring( 2 );
          }

          //DirectoryScanner chokes on "//".  Collapse these into "/" for local files
          filePath=filePath.replace( "//", "/" );
          File localFile = new File( filePath );
          DirectoryScanner scanner = new DirectoryScanner();
          //Windows is case-insensitive.  For consistent behavior on this platform disable case sensitivity
          if( Utils.isWindows() ){
            scanner.setCaseSensitive( false );
          }
          if( ! localFile.isAbsolute() ) {
            scanner.setBasedir( "." );
          }
          scanner.setIncludes( new String[]{ filePath } );
          scanner.scan();
          String[] files = scanner.getIncludedFiles();

          if( files.length == 0 ){
            throw new FileNotFoundException( "No such file: "+filePath );
          }

          for( String file : files ) {
            numFilesValidated++;
            long startMs = System.currentTimeMillis();

            LOG.info( getValidatingXMLSchemaLogMessage( file, catalogFile ) );
            validator.validate( file );
            if( schematronFile != null ) {
              LOG.info( String.format( "Validating file %s against Schematron rules (%s)", file, schematronFile ) );
              schematronValidator.validate( file, schematronFile );
            }

            LOG.info( "Validation successful, took " + ( System.currentTimeMillis() - startMs ) + " ms" );
          }
        }
        //otherwise this is a URL and nothing further needed
        else {
          numFilesValidated++;
          long startMs = System.currentTimeMillis();

          LOG.info( getValidatingXMLSchemaLogMessage( filePath, catalogFile ) );
          validator.validate( filePath );
          if( schematronFile != null ) {
            LOG.info( String.format( "Validating file %s against Schematron rules (%s)\n", filePath, schematronFile ) );
            schematronValidator.validate( filePath, schematronFile );
          }

          LOG.info( "Validation successful, took " + ( System.currentTimeMillis() - startMs ) + " ms" );
        }
      }catch( ValidationException ve ){
        //just accumulate validation errors and throw a single Exception
        errors.addAll( ve.getValidationErrors() );
      }
    }

//    System.out.printf( "%d file(s) validated\n", numFilesValidated );

    //if validation errors have been encountered, throw them in a single ValidationException
    if( errors.size() > 0 ){
      throw new ValidationException( errors );
    }

    return numFilesValidated;
  }

  private String getValidatingXMLSchemaLogMessage( String xsdOrXmlFile, String catalogFile ){
    String msg = "Validating file "+xsdOrXmlFile+" against XML schema";
    if( catalogFile != null ){
      msg += " using the following catalog(s): "+ catalogFile;
    }
    return msg;
  }

  /**
   * Set whether remote (non-local) schema files are resolved.  Remote schema resolution can be used to get validation
   * software to participate in denial of service attacks and other malicious activities. False by default
   */
  public void setAllowingRemoteResources( boolean allowingRemoteResources ){
    this.allowingRemoteResources = allowingRemoteResources;
  }

  public boolean isAllowingRemoteResources() {
    return allowingRemoteResources;
  }

  private static void printUsage(){
    String simpleCatalog = "  <!DOCTYPE catalog PUBLIC \"-//OASIS//DTD Entity Resolution XML Catalog V1.0//EN\" \"http://www.oasis-open.org/committees/entity/release/1.0/catalog.dtd\">\n" +
      "  <catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n" +
      "    <system systemId=\"http://www.w3.org/1999/xlink.xsd\" uri=\"local-schemas/xlink.xsd\"/>\n" +
      "    <rewriteSystem systemIdStartString=\"http://schemas.opengis.net\" rewritePrefix=\"local-schemas/net/opengis\"/>\n" +
      "  </catalog>";

    System.err.println( "Usage: crux.jar [OPTIONS] [XML/XSD FILES]\t (XML/XSD files may include wildcards)\n");
    System.err.println( "Options:" );
    System.err.println( "\t -c CATALOG_FILE" );
    System.err.println( "\t -s SCHEMATRON_FILE" );
    System.err.println( "\t -r   (allow remote schema resolution - disabled by default)" );
    System.err.println( "\t -d   (enable debugging messages)\n" );
    System.err.println( "A simple catalog file which would utilize a local copy of http://www.w3.org/1999/xlink.xsd would be:\n\n"+simpleCatalog);
    System.err.println();
    System.err.println( "Examples:\n");
    System.err.println( "  [crux.jar] file.xml                     -validation of a local XML file based on schema locations in the file" );
    System.err.println( "  [crux.jar] *.xml                        -validation of *.xml local XML files based on schema locations in the files" );
    System.err.println( "  [crux.jar] file?.xml                    -validation of local XML files like file1.xml, fileA.xml, and so on based on schema locations in the files" );
    System.err.println( "  [crux.jar] http://foo.org/myschema.xsd  -validation of a remote schema" );
    System.err.println( "  [crux.jar] file.xml -c catalog.xml      -validation of a local XML file using local copies of schemas as defined in catalog.xml" );
    System.err.println( "  [crux.jar] file.xml -s rules.sch        -validation of a local XML file against both the internally-defined XML schema and against Schematron rules" );
    System.err.println( "  [crux.jar] myschema.xsd                 -validation of a local schema" );
    System.err.println();
  }

  public static void main(String[] args){
    if( args.length < 1 ){
      printUsage();
      System.exit( 1 );
    }

    List<String> argsList = new ArrayList<>( Arrays.asList( args ) );
    String catalogLocation = null;
    String schematronFile = null;
    boolean allowRemoteResources = false;
    for( int i = 0; i < argsList.size(); i++ ){
      String arg = argsList.get( i );
      switch( arg ) {
        case "-c":
          //if there is a next argument...
          if( argsList.size() > ( i + 1 ) ) {
            catalogLocation = argsList.get( i + 1 );
            argsList.remove( i );  //remove the -c from the list
            argsList.remove( i );  //remove the -c target from the list (this is now the ith index)
            i--;  //after we remove items the index should remain the same
          }
          else {
            System.err.println( "No catalog file specified with the -c option" );
            System.exit( 1 );
          }
          break;
        case "-s":
          //if there is a next argument...
          if( argsList.size() > ( i + 1 ) ) {
            schematronFile = argsList.get( i + 1 );
            argsList.remove( i );  //remove the -s from the list
            argsList.remove( i );  //remove the -s target from the list (this is now the ith index)
            i--;  //after we remove items the index should remain the same
          }
          else {
            System.err.println( "No Schematron file specified with the -s option" );
            System.exit( 1 );
          }
          break;
        case "-d":
          ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger( "edu.ucar.ral.crux" );
          rootLogger.setLevel( Level.DEBUG );
          argsList.remove( i );
          i--;
          break;
        case "-r":
          allowRemoteResources = true;
          argsList.remove( i );
          i--;
          break;
        default:
          if( arg.startsWith( "-" )) {
            LOG.warn( "Unknown command line argument: "+arg );
            printUsage();
            System.exit( 1 );
          }
          //other remaining arguments not prefixed with "-" are XML or XSD files to validate
      }
    }

    Crux crux = new Crux();
    crux.setAllowingRemoteResources( allowRemoteResources );
    boolean validationFailed = false;
    try{
      int numValidatedFiles = 
        crux.validate( catalogLocation, schematronFile, argsList.toArray( new String[argsList.size()] ) );
    }
    catch( ValidationException e ) {
      validationFailed = true;
      if( e.getCause() != null ){
        //the validation failed due to an I/O or other nested exception.  Print this
        e.getCause().printStackTrace();
      }
      else {
        for( ValidationError failure : e.getValidationErrors() ) {
          LOG.error( "Validation FAILED on " + failure );
        }
      }
    }
    catch( FileNotFoundException e ){
      //print out a more readable message rather than the full stack trace
      LOG.info( e.getMessage() );
      System.exit( 1 );
    }
    catch( Exception e ){
      validationFailed = true;
      e.printStackTrace();
    }

    //return the correct error code
    if( validationFailed ){
      System.exit( 1 );
    }
  }
}