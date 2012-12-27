package SevenZip.Compression.RangeCoder;

import SevenZip.Compression.LZMA.Base;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class EncoderLearningTest {

    private Encoder _encoder;
    private ByteArrayOutputStream _stream;
    private short[] _probs;

    @Before
    public void setUp() throws Exception {
        _stream = new ByteArrayOutputStream();
        _encoder = new Encoder();
        _encoder.setStream(_stream);
        _encoder.init();
        _probs = new short[Base.kNumStates];
        RangeBase.InitBitModels(_probs);
    }

    @Test
    public void testZeros() throws Exception {
        assertThat(encode(0, 0, 0), is("00 00 00 00 00"));
    }

    @Test
    public void testOnes() throws Exception {
        assertThat(encode(1, 1, 1), is("00 dc f8 3c 00"));
    }

    @Test
    public void test_different_length_expansion() throws Exception {
        assertThat(encode(), is("00 00 00 00 00"));
        assertThat(encode(0), is("00 00 00 00 00"));
        assertThat(encode(1), is("00 7f ff fc 00"));
        assertThat(encode(0,1,0,1,0, 1,0,1,0,1), is("00 56 fa d6 38 2c"));
        assertThat(encode(1,1,1,1,1, 1,1,1,1,1), is("00 ff 2e 08 28 00"));
        assertThat(encode(0,1,0,1,0, 1,0,1,0,1, 0,1,0,1,0, 1,0,1,0,1), is("00 57 0d 5d 83 4f 8e"));
        assertThat(encode(1,1,1,1,1, 1,1,1,1,1, 1,1,1,1,1, 1,1,1,1,1), is("00 ff fb 88 c9 99"));
    }

    @Test
    public void test_show_probs() throws Exception {
        int[] bits = {0, 0, 0, 0, 1, 1, 1, 1};
        System.out.printf("initial:              prob = %4d = 0x%3x = %f\n", _probs[4], _probs[4], _probs[4] / 2048.0);
        for (int bit : bits) {
            _encoder.encode(_probs, 4, bit);
            System.out.printf("after encoding bit %d: prob = %4d = 0x%3x = %f\n", bit, _probs[4], _probs[4], _probs[4] / 2048.0);
        }
    }

    @Test
    public void test_directBits_multiple_calls() throws Exception {
        _encoder.encodeDirectBits(0x1, 2);
        _encoder.encodeDirectBits(0xd, 4);
        _encoder.flush();
        assertThat(toString(_stream), is("00 73 ff ff fc"));
    }

    @Test
    public void test_directBits_single_call() throws Exception {
        _encoder.encodeDirectBits(0x1d, 6);
        _encoder.flush();
        assertThat(toString(_stream), is("00 73 ff ff fc"));
    }

    private String toString(ByteArrayOutputStream stream) {
        final byte[] bytes = stream.toByteArray();
        final StringBuilder sb = new StringBuilder();
        for (byte aByte : bytes) {
            sb.append(String.format("%02x ", aByte));
        }
        return sb.toString().trim();
    }

    private String encode(int... bits) throws IOException {
        _stream = new ByteArrayOutputStream();
        _encoder = new Encoder();
        _encoder.setStream(_stream);
        _encoder.init();
        _probs = new short[Base.kNumStates];
        RangeBase.InitBitModels(_probs);
        for (int bit : bits) {
            _encoder.encode(_probs, 4, bit);
        }
        _encoder.flush();
        return toString(_stream);
    }

}
