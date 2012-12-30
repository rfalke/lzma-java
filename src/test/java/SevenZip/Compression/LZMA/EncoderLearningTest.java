package SevenZip.Compression.LZMA;

import SevenZip.LogFormatter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EncoderLearningTest {
    private static final Logger log = Logger.getLogger(EncoderLearningTest.class.getName());

    @Before
    public void setUp() throws Exception {
        Logger.getLogger("").setLevel(Level.FINE);
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            handler.setLevel(Level.ALL);
            handler.setFormatter(new LogFormatter());
        }
    }

    @After
    public void resetLogger() throws Exception {
        Logger.getLogger("").setLevel(Level.INFO);
    }

    @Test
    public void testSimple() throws IOException {
        encode(new byte[]{99, 100, 98, 100, 100, 100, 100, 100, 100, 100, 100});
        encode(new byte[]{100, 101, 102, 103, 104, 105, 101, 102, 101, 102});
        encode(new byte[]{100, 101, 102, 103, 101, 104, 101, 101, 101});
        encode(new byte[]{100, 100, 100, 101, 100, 100, 100, 101, 100, 100, 100, 101});
    }

    private static String encode(byte... bytes) throws IOException {
        log.info("############ Encode " + Arrays.toString(bytes));
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final Encoder encoder = new Encoder();
        encoder.Code(inputStream, outputStream, -1, -1, null);
        return SevenZip.Compression.RangeCoder.EncoderLearningTest.toString(outputStream);
    }

}
