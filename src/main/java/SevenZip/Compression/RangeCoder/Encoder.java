package SevenZip.Compression.RangeCoder;

import java.io.IOException;
import java.io.OutputStream;

public class Encoder extends RangeBase {

    private OutputStream _stream;
    private long _low;
    private int _range;
    private int _cacheSize;
    private int _cache;
    private long _position;

    public void setStream(OutputStream stream) {
        _stream = stream;
    }

    public void releaseStream() {
        _stream = null;
    }

    public void init() {
        _position = 0;
        _low = 0;
        _range = -1;
        _cacheSize = 1;
        _cache = 0;
    }

    public void flush() throws IOException {
        for (int i = 0; i < 5; i++) {
            shiftLow();
        }
        _stream.flush();
    }

    public void encode(short[] probs, int index, int symbol) throws IOException {
        assert symbol == 0 || symbol == 1;
        final int prob = probs[index];
        final int newBound = (_range >>> kNumBitModelTotalBits) * prob;
        if (symbol == 0) {
            _range = newBound;
            probs[index] = (short) (prob + ((kBitModelTotal - prob) >>> kNumMoveBits));
        } else {
            _low += (newBound & 0xFFFFFFFFL);
            _range -= newBound;
            probs[index] = (short) (prob - ((prob) >>> kNumMoveBits));
        }
        if ((_range & kTopMask) == 0) {
            _range <<= 8;
            shiftLow();
        }
    }

    public void encodeDirectBits(int v, int numTotalBits) throws IOException {
        for (int i = numTotalBits - 1; i >= 0; i--) {
            _range >>>= 1;
            if (((v >>> i) & 1) == 1) {
                _low += _range;
            }
            if ((_range & Encoder.kTopMask) == 0) {
                _range <<= 8;
                shiftLow();
            }
        }
    }

    public long getProcessedSizeAdd() {
        return _cacheSize + _position + 4;
    }

    private void shiftLow() throws IOException {
        final int LowHi = (int) (_low >>> 32);
        if (LowHi != 0 || _low < 0xFF000000L) {
            _position += _cacheSize;
            int temp = _cache;
            do {
                _stream.write(temp + LowHi);
                temp = 0xFF;
            }
            while (--_cacheSize != 0);
            _cache = (((int) _low) >>> 24);
        }
        _cacheSize++;
        _low = (_low & 0xFFFFFF) << 8;
    }
}
