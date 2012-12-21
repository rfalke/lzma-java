package SevenZip;

import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Arrays.asList;

public class LzmaAloneTest {

    @Rule
    public TemporaryFolder _temporaryFolder = new TemporaryFolder();

    @Test
    public void testRoundTrip() throws Exception {
        final File inputFile = new File("src/test/java/SevenZip/firefox.exe");
        assertRoundTrip(inputFile, "93c6983fcfa73e55099a11ee13139687", 138940);
        assertRoundTrip(inputFile, "4b9287512dcf72b094abafbd5fbfda85", 138946, "-eos");
        assertRoundTrip(inputFile, "385ef9694b5d0640fd372c99cec1d575", 356822, "-d0");
        assertRoundTrip(inputFile, "81b9ab49744b242c4e5a0274ae5a83d3", 150508, "-fb5");
        assertRoundTrip(inputFile, "44e59bfa0128c6dcfde164598e180e92", 138711, "-fb273");
        assertRoundTrip(inputFile, "8ebbd8dc6c1a1dd2c1803659a4a2b978", 143351, "-lc0");
        assertRoundTrip(inputFile, "f7a9f4ce9c7853c07445b41cca75c58c", 144829, "-lc8");
        assertRoundTrip(inputFile, "27fba851ee64468dc5391d4a0f430ab7", 137620, "-lp1");
        assertRoundTrip(inputFile, "377337634457f7017760e45129760c7d", 141530, "-lp4");
        assertRoundTrip(inputFile, "563da117b34b52358e24d6e5b16d093d", 142879, "-pb0");
        assertRoundTrip(inputFile, "cbbff9f4722065bec54336a7d3d49832", 140046, "-pb4");
        assertRoundTrip(inputFile, "126f88731f968265bf163b7f7b5521db", 138877, "-mfbt2");
    }

    @Test
    public void testBenchmark() throws Exception {
        List<String> args = new ArrayList<String>();
        args.add("b");
        args.add("2");
        LzmaAlone.main(args.toArray(new String[args.size()]));
    }

    private void assertRoundTrip(File inputFile, String md5, long expectedLength, String... compressionParameters) throws Exception {
        System.out.println("Trying with " + Arrays.toString(compressionParameters));
        final File compressedFile = compress(inputFile, compressionParameters);
        Assert.assertThat(compressedFile.length(), CoreMatchers.equalTo(expectedLength));
        Assert.assertThat(getMd5OfFile(compressedFile), CoreMatchers.equalTo(md5));
        final File decompressedFile = decompress(compressedFile);
        assertThatFilesAreEqual(inputFile, decompressedFile);
    }

    private String getMd5OfFile(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        final String md5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(fis);
        return md5;
    }

    private void assertThatFilesAreEqual(File expected, File actual) throws IOException {
        assertBinaryEquals(null, expected, actual);
    }

    private File compress(File inputFile, String... compressionParameters) throws Exception {
        List<String> args = new ArrayList<String>();
        args.add("e");
        args.addAll(asList(compressionParameters));
        args.add(inputFile.getCanonicalPath());
        final File tempFile = _temporaryFolder.newFile("compressed");
        args.add(tempFile.getCanonicalPath());
        LzmaAlone.main(args.toArray(new String[args.size()]));
        return tempFile;
    }

    private File decompress(File inputFile, String... decompressionParameters) throws Exception {
        List<String> args = new ArrayList<String>();
        args.add("d");
        args.addAll(asList(decompressionParameters));
        args.add(inputFile.getCanonicalPath());
        final File tempFile = _temporaryFolder.newFile("decompressed");
        args.add(tempFile.getCanonicalPath());
        LzmaAlone.main(args.toArray(new String[args.size()]));
        return tempFile;
    }

    /**
     * Asserts that two binary files are equal. Throws an
     * <tt>AssertionFailedError</tt> if they are not.<p>
     */
    public static void assertBinaryEquals(String message,
                                          File expected,
                                          File actual) throws IOException {
        Assert.assertNotNull(message, expected);
        Assert.assertNotNull(message, actual);

        Assert.assertTrue("File does not exist [" + expected.getAbsolutePath() + "]", expected.exists());
        Assert.assertTrue("File does not exist [" + actual.getAbsolutePath() + "]", actual.exists());

        Assert.assertTrue("Expected file not readable", expected.canRead());
        Assert.assertTrue("Actual file not readable", actual.canRead());

        FileInputStream eis = null;
        FileInputStream ais = null;

        try {
            eis = new FileInputStream(expected);
            ais = new FileInputStream(actual);

            Assert.assertNotNull(message, expected);
            Assert.assertNotNull(message, actual);

            final int bufferSize = 1024 * 1024;
            byte[] expBuff = new byte[bufferSize];
            byte[] actBuff = new byte[bufferSize];

            long pos = 0;
            while (true) {
                int expLength = eis.read(expBuff, 0, bufferSize);
                int actLength = ais.read(actBuff, 0, bufferSize);

                if (expLength < actLength) {
                    Assert.fail("actual file is longer");
                }
                if (expLength > actLength) {
                    Assert.fail("actual file is shorter");
                }

                if (expLength <= 0) {
                    return;
                }

                for (int i = 0; i < expBuff.length; ++i) {
                    if (expBuff[i] != actBuff[i]) {
                        String formatted = "";
                        if (message != null) {
                            formatted = message + " ";
                        }
                        Assert.fail(formatted + "files differ at byte " + (pos + i + 1));  // i starts at 0 so +1
                    }
                }

                pos += expBuff.length;
            }
        } finally {
            eis.close();
            ais.close();
        }
    }
}
