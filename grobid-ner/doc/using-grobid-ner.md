At the present time, Grobid NER is a java library. 

The NER can be called as follow. 

## Building with maven

When using maven, you need to include in your pom file the path to the grobid-core and grobid-ner jar files, for instance as follow (replace `0.2.14` by the valid `<current version>`):

	<dependency>
	    <groupId>org.grobid.core</groupId>
	    <artifactId>grobid</artifactId>
	    <version>0.2.14</version>
	    <scope>system</scope>
	    <systemPath>${project.basedir}/lib/grobid-core-0.2.10.jar</systemPath>
	</dependency>

	<dependency>
	    <groupId>org.grobid.ner</groupId>
	    <artifactId>grobid-ner</artifactId>
	    <version>0.2.1</version>
	    <scope>system</scope>
	    <systemPath>${project.basedir}/lib/grobid-ner-0.2.1.jar</systemPath>
	</dependency>
	

## API call

When using Grobid NER, you have to initiate a context with the path to the Grobid resources, the following class give an example of usage:

        import org.grobid.core.*;
        import org.grobid.core.data.*;
        import org.grobid.core.factory.*;
        import org.grobid.core.mock.*;
        import org.grobid.core.utilities.*;
		import org.grobid.core.data.Entity;
		import org.grobid.core.data.Sense;
		import org.grobid.core.engines.NERParser;

		...
		
	try {
			String pGrobidHome = "/Users/lopez/grobid/grobid-home";
			String pGrobidProperties = "/Users/lopez/grobid/grobid-home/config/grobid.properties";

			MockContext.setInitialContext(pGrobidHome, pGrobidProperties);		
			GrobidProperties.getInstance();
			LibraryLoader.load();
			
			System.out.println(">>>>>>>> GROBID_HOME="+GrobidProperties.get_GROBID_HOME_PATH());
		
			NERParser nerParser = new NERParser();

			List<Entity> results = nerParser.extractNE(text);
			
			for(Entity entity: results) {
				System.out.println(entity.toJson());
			}
		} 
		catch (Exception e) {
			// If an exception is generated, print a stack trace
			e.printStackTrace();
		} 
		finally {
			try {
				MockContext.destroyInitialContext();
			} 
			catch (Exception e) {
				e.printStackTrace();
			}
		}

The context paths (pGrobidHome and pGrobidProperties) can be set by a property file, or for a web application by a web.xml file (see for instance grobid-service).