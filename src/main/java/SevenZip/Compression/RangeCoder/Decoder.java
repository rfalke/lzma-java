package SevenZip.Compression.RangeCoder;

import java.io.IOException;
import java.io.InputStream;

public class Decoder extends RangeBase {
    private int _range;
    private int _code;
    private InputStream _stream;

    public final void SetStream(InputStream stream) {
        _stream = stream;
    }

    public final void ReleaseStream() {
        _stream = null;
    }

    public final void Init() throws IOException {
        _code = 0;
        _range = -1;
        for (int i = 0; i < 5; i++) {
            _code = (_code << 8) | _stream.read();
        }
    }

    public final int DecodeDirectBits(int numTotalBits) throws IOException {
        int result = 0;
        for (int i = numTotalBits; i != 0; i--) {
            _range >>>= 1;
            final int t = ((_code - _range) >>> 31);
            _code -= _range & (t - 1);
            result = (result << 1) | (1 - t);

            if ((_range & kTopMask) == 0) {
                _code = (_code << 8) | _stream.read();
                _range <<= 8;
            }
        }
        return result;
    }

    public int DecodeBit(short[] probs, int index) throws IOException {
        final int prob = probs[index];
        final int newBound = (_range >>> kNumBitModelTotalBits) * prob;
        if ((_code ^ 0x80000000) < (newBound ^ 0x80000000)) {
            _range = newBound;
            probs[index] = (short) (prob + ((kBitModelTotal - prob) >>> kNumMoveBits));
            if ((_range & kTopMask) == 0) {
                _code = (_code << 8) | _stream.read();
                _range <<= 8;
            }
            return 0;
        } else {
            _range -= newBound;
            _code -= newBound;
            probs[index] = (short) (prob - ((prob) >>> kNumMoveBits));
            if ((_range & kTopMask) == 0) {
                _code = (_code << 8) | _stream.read();
                _range <<= 8;
            }
            return 1;
        }
    }
}
