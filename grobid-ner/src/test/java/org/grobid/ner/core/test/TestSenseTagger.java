package org.grobid.ner.core.test;

import org.apache.commons.io.FileUtils;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.ner.core.data.Sense;
import org.grobid.ner.core.engines.SenseTagger;
import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * @author Patrice Lopez
 */
//@Ignore
public class TestSenseTagger extends EngineTest {

    public File getResourceDir(String resourceDir) {
        File file = new File(resourceDir);
        if (!file.exists()) {
            if (!file.mkdirs()) {
                throw new GrobidException("Cannot start test, because test resource folder is not correctly set.");
            }
        }
        return (file);
    }

    @Test
    public void testSenseTagger() throws Exception {
        File textFile =
                new File(this.getResourceDir("./src/test/resources/").getAbsoluteFile() + "/test.txt");
        if (!textFile.exists()) {
            throw new GrobidException("Cannot start test, because test resource folder is not correctly set.");
        }
        String text = FileUtils.readFileToString(textFile);

        SenseTagger tagger = new SenseTagger();

        List<Sense> senses = tagger.extractSenses(text);
        if (senses != null) {
            for (Sense sense : senses) {
                System.out.print(text.substring(sense.getOffsetStart(), sense.getOffsetEnd()) + "\t");
                System.out.println(sense.toString());
            }
        } else {
            System.out.println("No sense found.");
        }
    }

}