package org.grobid.trainer;

import org.grobid.core.GrobidModels;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.exceptions.GrobidResourceException;
import org.grobid.core.features.FeaturesVectorNER;
import org.grobid.core.lexicon.Lexicon;
import org.grobid.core.lexicon.NERLexicon;
import org.grobid.core.mock.MockContext;
import org.grobid.core.utilities.GrobidProperties;
import org.grobid.core.utilities.OffsetPosition;
import org.grobid.trainer.sax.ReutersSaxHandler;
import org.grobid.trainer.sax.SemDocSaxHandler;
import org.grobid.trainer.evaluation.EvaluationUtilities;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


/**
 * Create the English language tagging model for named entities 
 *
 * @author Patrice Lopez
 */
public class NERTrainer extends AbstractTrainer {

	private NERLexicon nerLexicon = NERLexicon.getInstance();
	protected Lexicon lexicon = Lexicon.getInstance();
	
	private String reutersPath = null;
	private String idiliaPath = null;
	private String nerCorpusPath = null;
	
	private static int LIMIT = 25000; // limit of files to process per zip archives, -1 if no limit
	private static int GLOBAL_LIMIT = 500000; // overall limit of files to process, -1 if no limit

	// this is the list of Reuters dates for the files used in the CoNLL NER annotation set
	private static List<String> coreSet = Arrays.asList("19960822", "19960823", "19960824", "19960825", "19960826",
            "19960827", "19960828", "19960829", "19960830", "19960831", "19961206", "19961207");

    public NERTrainer() {
        super(GrobidModels.ENTITIES_NER);

        // adjusting CRF training parameters for this model
		epsilon = 0.000001;
		window = 20;

		// read additional properties for this sub-project to get the paths to the resources
		Properties prop = new Properties();
		InputStream input = null;
		try {
			input = new FileInputStream("src/main/resources/grobid-ner.properties");

			// load the properties file
			prop.load(input);

			// get the property value
			reutersPath = prop.getProperty("grobid.ner.reuters.paths");
			idiliaPath = prop.getProperty("grobid.ner.reuters.idilia_path");
			nerCorpusPath = prop.getProperty("grobid.ner.extra_corpus");
		} 
		catch (IOException ex) {
			throw new GrobidResourceException(
				"An exception occured when accessing/reading the grobid-ner property file.", ex);
		} 
		finally {
			if (input != null) {
				try {
					input.close();
				} 
				catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
    }

	@Override
	/**
     * Add the selected features to the training data for the NER model
     */
    public int createCRFPPData(File sourcePathLabel,
                               File outputPath) {
		return createCRFPPData(sourcePathLabel, outputPath, null, 1.0);
	}
	
	/**
	 * Add the selected features to a NER example set 
	 * 
	 * @param corpusDir
	 *            a path where corpus files are located
	 * @param trainingOutputPath
	 *            path where to store the temporary training data
	 * @param evalOutputPath
	 *            path where to store the temporary evaluation data
	 * @param splitRatio
	 *            ratio to consider for separating training and evaluation data, e.g. 0.8 for 80% 
	 * @return the total number of used corpus items 
	 */
	@Override
	public int createCRFPPData(final File corpusDir, 
							final File trainingOutputPath, 
							final File evalOutputPath, 
							double splitRatio) {
        int totalExamples = 0;
        try {
            System.out.println("sourcePathLabel: " + corpusDir);
			if (trainingOutputPath != null)
				System.out.println("outputPath for training data: " + trainingOutputPath);
			if (evalOutputPath != null)
				System.out.println("outputPath for evaluation data: " + evalOutputPath);

			// the file for writing the training data
			OutputStream os2 = null;
			Writer writer2 = null;
			if (trainingOutputPath != null) {
				os2 = new FileOutputStream(trainingOutputPath);
				writer2 = new OutputStreamWriter(os2, "UTF8");
			}

			// the file for writing the evaluation data
			OutputStream os3 = null;
			Writer writer3 = null;
			if (evalOutputPath != null) {
				os3 = new FileOutputStream(evalOutputPath);
				writer3 = new OutputStreamWriter(os3, "UTF8");
			}

			// process the core trainig set first (set of Wikipedia and Reuters files corresponding to the 
			// CoNLL set but with the 26 class annotations manually corrected)
			totalExamples = processCore(writer2, writer3, splitRatio);

			// process the reuters corpus then (previous "core files" will be ignored)
//			totalExamples += processReutersCorpus(writer2, writer3, splitRatio);

			// we convert the tei files into the usual CRF label format
            // we process all tei files in the output directory
/*            File[] refFiles = corpusDir.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return (name.endsWith("*.tei") || name.endsWith("*.tei.xml"));
                }
            });

            if (refFiles == null) {
                return 0;
            }

     		System.out.println(refFiles.length + " TEI files to process.");

            String name;
            int n = 0;
            for (File teifile : refFiles) {	
				Writer writer = null;
				if ( (writer2 == null) && (writer3 != null) )
					writer = writer3;
				if ( (writer2 != null) && (writer3 == null) )
					writer = writer2;
				else {		
					if (Math.random() <= splitRatio)
						writer = writer2;
					else 
						writer = writer3;
				}
				
				// to store unit term positions
                List<List<OffsetPosition>> nerTokenPositions = new ArrayList<List<OffsetPosition>>();
				
				// we simply need to retokenize the token following Grobid approach										
				List<String> labeled = new ArrayList<String>();
			  	BufferedReader br = new BufferedReader(new InputStreamReader(
					new DataInputStream(new FileInputStream(teifile))));
			  	String line;
				List<String> input = new ArrayList<String>();
			  	while ((line = br.readLine()) != null)   {
					if (line.trim().length() == 0) {
						labeled.add("@newline");
						//nerTokenPositions.add(lexicon.inNERNames(input));
						input = new ArrayList<String>();
						continue;
					}
					int ind = line.indexOf("\t");
					if (ind == -1) {
						continue;
					}
					// we take the standard Grobid tokenizer
					StringTokenizer st2 = new StringTokenizer(line.substring(0, ind), 
						TextUtilities.fullPunctuations, true);
					while(st2.hasMoreTokens()) {
						String tok = st2.nextToken();
						if (tok.trim().length() == 0)
							continue;
						String label = line.substring(ind+1, line.length());
						if (label.equals("O")) {
							label = "other";
						}
						else if (label.startsWith("I-")) {
							label = label.substring(2,label.length());
						}
						else if (label.startsWith("B-")) {
							label = label;
						}
						labeled.add(tok + "\t" + label);
						input.add(tok);
					}					
				}
				//labeled.add("@newline");				                 				
				//nerTokenPositions.add(lexicon.inNERNames(input));
				 
                addFeatures(labeled, writer, null, null, null, null);
                writer.write("\n");
				br.close();
            }
			*/
			
			if (writer2 != null) {
				writer2.close();
			}
			if (os2 != null) {
				os2.close();
			}
			
			if (writer3 != null) {
				writer3.close();
			}
			if (os3 != null) {
				os3.close();
			}
        } catch (Exception e) {
            throw new GrobidException("An exception occured while running Grobid.", e);
        }
        return totalExamples;
    }


	private int processCore(Writer writerTraining, Writer writerEvaluation, double splitRatio) {
		int res = 0;
		File corpusDir1 = null;
		File corpusDir2 = null;
		File corpusDir3 = null;
		File corpusDir4 = null;
		try {
			// the "core" Reuters set is a the set of Reuters files corresponding to the 
			// CoNLL set, but with the 26 class annotations manually corrected
			// these annotated files are available in the corpus resource path of the model
			corpusDir1 = new File(nerCorpusPath + "/reuters.ner26.train");
			System.out.println("Path to 26-classes Reuters corpus CoNLL train set: " + corpusDir1.getPath());
			if (!corpusDir1.exists()) {
				throw new 
					GrobidException("Cannot start training, because corpus resource folder is not correctly set : " 
					+ corpusDir1.getPath());
			}
			
			corpusDir2 = new File(nerCorpusPath + "/reuters.ner26.testa");
			System.out.println("Path to 26-classes Reuters corpus CoNLL testa set: " + corpusDir2.getPath());
			if (!corpusDir2.exists()) {
				throw new 
					GrobidException("Cannot start training, because corpus resource folder is not correctly set : " 
					+ corpusDir2.getPath());
			}
		
			corpusDir3 = new File(nerCorpusPath + "/reuters.ner26.testa");
			System.out.println("Path to 26-classes Reuters corpus CoNLL testb set: " + corpusDir3.getPath());
			if (!corpusDir3.exists()) {
				throw new 
					GrobidException("Cannot start training, because corpus resource folder is not correctly set : " 
					+ corpusDir3.getPath());
			}
			
			// Wikipedia annotated file
			corpusDir4 = new File("resources/dataset/ner/corpus/wikipedia.ner26.train");
			System.out.println("Path to 26-classes Wikipedia corpus train set: " + corpusDir4.getPath());
			if (!corpusDir4.exists()) {
				throw new 
					GrobidException("Cannot start training, because corpus resource folder is not correctly set : " 
					+ corpusDir4.getPath());
			}
			
			List<File> corpora = new ArrayList<File>();
			corpora.add(corpusDir1);
			//corpora.add(corpusDir2);
			//corpora.add(corpusDir3);
			corpora.add(corpusDir4);
			
			for(File corpusDir : corpora) {
				Writer writer = null;
				BufferedReader bufReader = new BufferedReader(
		                new InputStreamReader(new FileInputStream(corpusDir), "UTF-8"));
				// to store unit term positions
	            List<List<OffsetPosition>> locationPositions = new ArrayList<List<OffsetPosition>>();
	            List<List<OffsetPosition>> personTitlePositions = new ArrayList<List<OffsetPosition>>();
	            List<List<OffsetPosition>> organisationPositions = new ArrayList<List<OffsetPosition>>();		
				List<List<OffsetPosition>> orgFormPositions = new ArrayList<List<OffsetPosition>>();
				List<String> labeled = new ArrayList<String>();
				String line = null;
				while ((line = bufReader.readLine()) != null) {
					line = line.trim();
					// note that we work at sentence level
					if (line.startsWith("-DOCSTART-") || line.startsWith("-X-")) {
						// the balance of data between training and evaluation is realised
						// at document level 
						if ( (writerTraining == null) && (writerEvaluation != null) )
							writer = writerEvaluation;
						if ( (writerTraining != null) && (writerEvaluation == null) )
							writer = writerTraining;
						else {		
							if (Math.random() <= splitRatio)
								writer = writerTraining;
							else 
								writer = writerEvaluation;
						}
						continue;
					}

					if ((line.length() == 0) && (labeled.size() > 0)) {
						// sentence is complete
						locationPositions.add(lexicon.inLocationNames(labeled));
			            personTitlePositions.add(lexicon.inPersonTitleNames(labeled));
			            organisationPositions.add(lexicon.inOrganisationNames(labeled));
						orgFormPositions.add(lexicon.inOrgFormNames(labeled));	
					
						// this is mandatory for the correct setting of features
						labeled.add("@newline");
					
						addFeatures(labeled, writer, 
							locationPositions, personTitlePositions, organisationPositions, orgFormPositions);
						writer.write("\n");
					
						locationPositions = new ArrayList<List<OffsetPosition>>();
			            personTitlePositions = new ArrayList<List<OffsetPosition>>();
			            organisationPositions = new ArrayList<List<OffsetPosition>>();		
						orgFormPositions = new ArrayList<List<OffsetPosition>>();
						
						labeled = new ArrayList<String>();
						res++;
					}		
					else 
						labeled.add(line);	
				}	
				bufReader.close();			
			}
		}
		catch (IOException ex) {
			throw new GrobidResourceException(
				"An exception occured when accessing/reading the Reuters corpus files.", ex);
		} 
		finally {
		}
		return res;
	}
	

	private int processReutersCorpus(Writer writerTraining, Writer writerEvaluation, double splitRatio) {
		int res = 0;
		try {
			File corpusDir = new File(reutersPath);
			System.out.println("Path to Reuters corpus: " + reutersPath);
			if (!corpusDir.exists()) {
				throw new GrobidException("Cannot start training, because corpus resource folder is not correctly set : " 
					+ reutersPath);
			}
			
			File[] refFiles = corpusDir.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return (name.endsWith(".zip"));
                }
            });

			System.out.println(refFiles.length + " reuters zip files");

            if (refFiles == null) {
                return 0;
            }
			for (File thefile : refFiles) {	
				// we ignore the files in the core set
				if (coreSet.contains(thefile.getName().replace(".zip", ""))) {
					continue;
				}
				ZipFile zipFile = new ZipFile(thefile);
				System.out.println(thefile.getPath());
			    Enumeration<? extends ZipEntry> entries = zipFile.entries();
			    while(entries.hasMoreElements()) {
			        ZipEntry entry = entries.nextElement();
			        InputStream xmlStream = zipFile.getInputStream(entry);
			
					res += processReutersCorpus(xmlStream, entry, writerTraining, writerEvaluation, splitRatio);
					xmlStream.close();
					
					// as the number of files might be too important for development and debugging, 
					// we introduce an optional limit 
					if ( (LIMIT != -1) && (res > LIMIT) ) {
						break;
					}
			    }
				if ( (GLOBAL_LIMIT != -1) && (res > GLOBAL_LIMIT) ) {
					break;
				}
			}	
		}
		catch (IOException ex) {
			throw new GrobidResourceException(
				"An exception occured when accessing/reading the Reuters corpus zip files.", ex);
		} 
		finally {
		}
		return res;
	}
	
	private int processReutersCorpus(InputStream currentStream, 
									 ZipEntry entry,
									 Writer writerTraining, 
									 Writer writerEvaluation, 
									 double splitRatio) {
		try {
			// try to open the corresponding semdoc file
			String fileName = entry.getName();
System.out.println(fileName);
			File semdocFile = 
				new File(idiliaPath+"/"+fileName.substring(0,3)+"/" + fileName.replace(".xml",".semdoc.xml"));
			if (!semdocFile.exists()) {
				throw new GrobidException("Cannot start training, because corpus resource folder for semdoc file " +
				" is not correctly set : " 
					+ idiliaPath+"/"+fileName.substring(0,3)+"/" + fileName.replace(".xml",".semdoc.xml"));
			}

			ReutersSaxHandler reutersSax = new ReutersSaxHandler();
			
			// get a factory
	        SAXParserFactory spf = SAXParserFactory.newInstance();
	        spf.setValidating(false);
	        spf.setFeature("http://xml.org/sax/features/namespaces", false);
	        spf.setFeature("http://xml.org/sax/features/validation", false);
	        spf.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
	        spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

			//get a new instance of parser
	        SAXParser p = spf.newSAXParser();
	        p.parse(currentStream, reutersSax);

			SemDocSaxHandler semdocSax = new SemDocSaxHandler(reutersSax.getTextVector());

	        p = spf.newSAXParser();
	        p.parse(semdocFile, semdocSax);

			Writer writer = null;
			if ( (writerTraining == null) && (writerEvaluation != null) )
				writer = writerEvaluation;
			if ( (writerTraining != null) && (writerEvaluation == null) )
				writer = writerTraining;
			else {		
				if (Math.random() <= splitRatio)
					writer = writerTraining;
				else 
					writer = writerEvaluation;
			}

			if (semdocSax.getAnnotatedTextVector() != null) {
				// to store unit term positions
	            List<List<OffsetPosition>> locationPositions = new ArrayList<List<OffsetPosition>>();
	            List<List<OffsetPosition>> personTitlePositions = new ArrayList<List<OffsetPosition>>();
	            List<List<OffsetPosition>> organisationPositions = new ArrayList<List<OffsetPosition>>();		
				List<List<OffsetPosition>> orgFormPositions = new ArrayList<List<OffsetPosition>>();
				List<String> labeled = new ArrayList<String>();
				// default value for named entity feature
				
				for(String line : semdocSax.getAnnotatedTextVector()) {
					labeled.add(line);
					
					if (line.trim().equals("@newline")) {
						locationPositions.add(lexicon.inLocationNames(labeled));
			            personTitlePositions.add(lexicon.inPersonTitleNames(labeled));
			            organisationPositions.add(lexicon.inOrganisationNames(labeled));
						orgFormPositions.add(lexicon.inOrgFormNames(labeled));			
					
						addFeatures(labeled, writer, 
							locationPositions, personTitlePositions, organisationPositions, orgFormPositions);
						writer.write("\n");
						
						locationPositions = new ArrayList<List<OffsetPosition>>();
			            personTitlePositions = new ArrayList<List<OffsetPosition>>();
			            organisationPositions = new ArrayList<List<OffsetPosition>>();		
						orgFormPositions = new ArrayList<List<OffsetPosition>>();
						
						labeled = new ArrayList<String>();
					}
				}				
			}
		}
		catch (Exception e) {
            throw new GrobidException("An exception occured while running Grobid.", e);
        }
		return 1;
	}

    @SuppressWarnings({"UnusedParameters"})
    static public void addFeatures(List<String> texts,
                            Writer writer,
                            List<List<OffsetPosition>> locationPositions,
							List<List<OffsetPosition>> personTitlePositions,
							List<List<OffsetPosition>> organisationPositions,
							List<List<OffsetPosition>> orgFormPositions) {
        int totalLine = texts.size();
        int posit = 0;
		int sentence = 0;
		int currentLocationIndex = 0;
		int currentPersonTitleIndex = 0;
		int currentOrganisationIndex = 0;
		int currentOrgFormIndex = 0;
		List<OffsetPosition> localLocationPositions = null;
		List<OffsetPosition> localPersonTitlePositions = null;
		List<OffsetPosition> localOrganisationPositions = null;
		List<OffsetPosition> localOrgFormPositions = null;
		if (locationPositions.size() > sentence)
			localLocationPositions = locationPositions.get(sentence);
		if (personTitlePositions.size() > sentence)	
			localPersonTitlePositions = personTitlePositions.get(sentence);
		if (organisationPositions.size() > sentence)			
			localOrganisationPositions = organisationPositions.get(sentence);
		if (orgFormPositions.size() > sentence)			
			localOrgFormPositions = orgFormPositions.get(sentence);	
        boolean isLocationToken = false;
		boolean isPersonTitleToken = false;
		boolean isOrganisationToken = false;
		boolean isOrgFormToken = false;
        try {
			String previousLabel = null;
            for (String line : texts) {
				if (line.trim().equals("@newline")) {
					writer.write("\n");
	                writer.flush();	
					sentence++;
					previousLabel= null;
					if (locationPositions.size() > sentence)
						localLocationPositions = locationPositions.get(sentence);
					if (personTitlePositions.size() > sentence)		
						localPersonTitlePositions = personTitlePositions.get(sentence);
					if (organisationPositions.size() > sentence)	
						localOrganisationPositions = organisationPositions.get(sentence);
					if (orgFormPositions.size() > sentence)	
						localOrgFormPositions = orgFormPositions.get(sentence);
				}
				
				/*int ind = line.indexOf("\t");
				if (ind == -1) 
				 	ind = line.indexOf(" ");
				if (ind != -1) {		
				}*/
				
				// do we have a unit term at position posit?
				if ( (localLocationPositions != null) && (localLocationPositions.size() > 0) ) {
					for(int mm = currentLocationIndex; mm < localLocationPositions.size(); mm++) {
						if ( (posit >= localLocationPositions.get(mm).start) && 
							 (posit <= localLocationPositions.get(mm).end) ) {
							isLocationToken = true;
							currentLocationIndex = mm;
							break;
						}
						else if (posit < localLocationPositions.get(mm).start) {
							isLocationToken = false;
							break;
						}
						else if (posit > localLocationPositions.get(mm).end) {
							continue;
						}
					}
				}
				if ( (localPersonTitlePositions != null) && (localPersonTitlePositions.size() > 0) ) {
					for(int mm = currentPersonTitleIndex; mm < localPersonTitlePositions.size(); mm++) {
						if ( (posit >= localPersonTitlePositions.get(mm).start) && 
							 (posit <= localPersonTitlePositions.get(mm).end) ) {
							isPersonTitleToken = true;
							currentPersonTitleIndex = mm;
							break;
						}
						else if (posit < localPersonTitlePositions.get(mm).start) {
							isPersonTitleToken = false;
							break;
						}
						else if (posit > localPersonTitlePositions.get(mm).end) {
							continue;
						}
					}
				}
				if ( (localOrganisationPositions != null) && (localOrganisationPositions.size() > 0) ) {
					for(int mm = currentOrganisationIndex; mm < localOrganisationPositions.size(); mm++) {
						if ( (posit >= localOrganisationPositions.get(mm).start) && 
							 (posit <= localOrganisationPositions.get(mm).end) ) {
							isOrganisationToken = true;
							currentOrganisationIndex = mm;
							break;
						}
						else if (posit < localOrganisationPositions.get(mm).start) {
							isOrganisationToken = false;
							break;
						}
						else if (posit > localOrganisationPositions.get(mm).end) {
							continue;
						}
					}
				}
				if ( (localOrgFormPositions != null) && (localOrgFormPositions.size() > 0) ) {
					for(int mm = currentOrgFormIndex; mm < localOrgFormPositions.size(); mm++) {
						if ( (posit >= localOrgFormPositions.get(mm).start) && 
							 (posit <= localOrgFormPositions.get(mm).end) ) {
							isOrgFormToken = true;
							currentOrgFormIndex = mm;
							break;
						}
						else if (posit < localOrgFormPositions.get(mm).start) {
							isOrgFormToken = false;
							break;
						}
						else if (posit > localOrgFormPositions.get(mm).end) {
							continue;
						}
					}
				}

				// the "line" expected by the method FeaturesVectorNER.addFeaturesNER is the token
				// followed by the label, separated by a tab, and nothing else
				// in addition, the B- prefix for the labels must be injected here if not 
				// present
				String cleanLine = "";
				String[] pieces = line.split("\t");
				
				if (pieces.length > 0) {
					// first string is always the token
					cleanLine += pieces[0];
				}
				if (pieces.length > 1) {
					// second piece is the NER label, which could a single label or a list of labels in brackets 
					// separated by a comma
					if (pieces[1].startsWith("[")) {
						String rawlab = pieces[1].substring(1,pieces[1].length()-1);
						int ind = rawlab.indexOf(",");
						if (ind != -1) {
							rawlab = rawlab.substring(0,ind);
							if (rawlab.startsWith("B-") || rawlab.equals("O") || 
								rawlab.equals("0")) {
								cleanLine += "\t" + rawlab;
							}
							else {
								if ( (previousLabel == null) || (!previousLabel.equals(rawlab)) || 
									(previousLabel.equals("O")) || (previousLabel.equals("0")) ) {
									cleanLine += "\tB-" + rawlab;
								}
								else 
									cleanLine += "\t" + rawlab;
							}
						}
						else {
							//cleanLine += "\t" + labels;
							System.out.println("WARNING, format error:" + rawlab);
						}
						previousLabel = rawlab;
					}
					else {
						String rawlab = pieces[1];
						if (rawlab.startsWith("B-") || rawlab.equals("O") || 
							rawlab.equals("0")) {
							cleanLine += "\t" + rawlab;
						}
						else {
							if ( (previousLabel == null) || (!previousLabel.equals(rawlab)) || 
								(previousLabel.equals("O")) || (previousLabel.equals("0")) ) {
								cleanLine += "\tB-" + rawlab;
							}
							else 
								cleanLine += "\t" + rawlab;
						}
						previousLabel = rawlab;
					}
				}
                FeaturesVectorNER featuresVector =
                        FeaturesVectorNER.addFeaturesNER(cleanLine, isLocationToken, isPersonTitleToken, 
							isOrganisationToken, isOrgFormToken);
                if (featuresVector.label == null)
                    continue;
                writer.write(featuresVector.printVector()+"\n");
                writer.flush();
                posit++;
				isLocationToken = false;
				isPersonTitleToken = false;
				isOrganisationToken = false;
				isOrgFormToken = false;
            }
        } 
		catch (Exception e) {
            throw new GrobidException("An exception occured while running Grobid.", e);
        }
    }

    /**
     * Command line execution.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
		try {
			String pGrobidHome = "../grobid-home";
			String pGrobidProperties = "../grobid-home/config/grobid.properties";
		
			MockContext.setInitialContext(pGrobidHome, pGrobidProperties);
		    GrobidProperties.getInstance();

	        NERTrainer trainer = new NERTrainer();
	
	        AbstractTrainer.runTraining(trainer);
	        AbstractTrainer.runEvaluation(trainer);
		}
		catch (Exception e) {
		    e.printStackTrace();
		}
		finally {
			try {
				MockContext.destroyInitialContext();
			}
			catch(Exception e) {
				e.printStackTrace();
			}
		}
    }
}