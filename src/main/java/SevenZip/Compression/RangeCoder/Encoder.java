package SevenZip.Compression.RangeCoder;

import java.io.IOException;
import java.io.OutputStream;

public class Encoder extends RangeBase {
    public static final int kNumBitPriceShiftBits = 6;

    private OutputStream Stream;
    private long Low;
    private int Range;
    private int _cacheSize;
    private int _cache;
    private long _position;

    public void SetStream(OutputStream stream) {
        Stream = stream;
    }

    public void ReleaseStream() {
        Stream = null;
    }

    public void Init() {
        _position = 0;
        Low = 0;
        Range = -1;
        _cacheSize = 1;
        _cache = 0;
    }

    public void FlushData() throws IOException {
        for (int i = 0; i < 5; i++) {
            ShiftLow();
        }
    }

    public void FlushStream() throws IOException {
        Stream.flush();
    }

    public void ShiftLow() throws IOException {
        int LowHi = (int) (Low >>> 32);
        if (LowHi != 0 || Low < 0xFF000000L) {
            _position += _cacheSize;
            int temp = _cache;
            do {
                Stream.write(temp + LowHi);
                temp = 0xFF;
            }
            while (--_cacheSize != 0);
            _cache = (((int) Low) >>> 24);
        }
        _cacheSize++;
        Low = (Low & 0xFFFFFF) << 8;
    }

    public void EncodeDirectBits(int v, int numTotalBits) throws IOException {
        for (int i = numTotalBits - 1; i >= 0; i--) {
            Range >>>= 1;
            if (((v >>> i) & 1) == 1) {
                Low += Range;
            }
            if ((Range & Encoder.kTopMask) == 0) {
                Range <<= 8;
                ShiftLow();
            }
        }
    }

    public long GetProcessedSizeAdd() {
        return _cacheSize + _position + 4;
    }

    public void Encode(short[] probs, int index, int symbol) throws IOException {
        int prob = probs[index];
        int newBound = (Range >>> kNumBitModelTotalBits) * prob;
        if (symbol == 0) {
            Range = newBound;
            probs[index] = (short) (prob + ((kBitModelTotal - prob) >>> kNumMoveBits));
        } else {
            Low += (newBound & 0xFFFFFFFFL);
            Range -= newBound;
            probs[index] = (short) (prob - ((prob) >>> kNumMoveBits));
        }
        if ((Range & kTopMask) == 0) {
            Range <<= 8;
            ShiftLow();
        }
    }

}
