package SevenZip.Compression.RangeCoder;

import java.io.IOException;

public class BitTreeDecoder {

    private final short[] Models;
    private final int NumBitLevels;

    public BitTreeDecoder(int numBitLevels) {
        NumBitLevels = numBitLevels;
        Models = new short[1 << numBitLevels];
    }

    public void Init() {
        RangeBase.InitBitModels(Models);
    }

    public int Decode(RangeDecoder rangeDecoder) throws IOException {
        int m = 1;
        for (int bitIndex = NumBitLevels; bitIndex != 0; bitIndex--) {
            m = (m << 1) + rangeDecoder.DecodeBit(Models, m);
        }
        return m - (1 << NumBitLevels);
    }

    public int ReverseDecode(RangeDecoder rangeDecoder) throws IOException {
        int m = 1;
        int symbol = 0;
        for (int bitIndex = 0; bitIndex < NumBitLevels; bitIndex++) {
            final int bit = rangeDecoder.DecodeBit(Models, m);
            m <<= 1;
            m += bit;
            symbol |= (bit << bitIndex);
        }
        return symbol;
    }
}
