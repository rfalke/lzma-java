package SevenZip.Compression.RangeCoder;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static java.lang.Integer.highestOneBit;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class BitTreeEncoderLearningTest {

    @Test
    public void test_getPrice() throws IOException {
        final BitTreeEncoder treeEncoder = new BitTreeEncoder(3);
        treeEncoder.Init();
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final Encoder rangeEncoder = new Encoder();
        rangeEncoder.setStream(stream);
        rangeEncoder.init();

        treeEncoder.encode(rangeEncoder, 3);
        assertThat(treeEncoder.getPrice(0), is(194));
        assertThat(treeEncoder.getPrice(1), is(194));
        assertThat(treeEncoder.getPrice(2), is(192));
        assertThat(treeEncoder.getPrice(3), is(186));
        assertThat(treeEncoder.getPrice(4), is(196));
        assertThat(treeEncoder.getPrice(5), is(196));
        assertThat(treeEncoder.getPrice(6), is(196));
        assertThat(treeEncoder.getPrice(7), is(196));
    }

    @Test
    public void print_prices() {
        for (int i = 1; i < ProbPrices.ProbPrices.length; i++) {
            final int probPrice = ProbPrices.ProbPrices[i];
            final int prob = i << ProbPrices.kNumMoveReducingBits;
            final int bits = Integer.numberOfTrailingZeros(highestOneBit(i)) + 1;
            final int offset1 = i - highestOneBit(i);
            final int offset2 = 2 * highestOneBit(i) - i;
            System.out.printf("i=%3d prob = %4d = 0x%3x = %f price=%d bits=%d = %d offset=%d,%d\n", i, prob, prob, prob / 2048.0, probPrice, bits, (8 - bits) * 64, offset1, offset2);
        }
    }
}
