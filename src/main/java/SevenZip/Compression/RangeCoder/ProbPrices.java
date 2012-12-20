package SevenZip.Compression.RangeCoder;

public class ProbPrices {
    private static final int kNumMoveReducingBits = 2;
    private static final int[] ProbPrices = new int[RangeBase.kBitModelTotal >>> kNumMoveReducingBits];

    static {
        final int kNumBits = (RangeBase.kNumBitModelTotalBits - kNumMoveReducingBits);
        for (int i = kNumBits - 1; i >= 0; i--) {
            final int start = 1 << (kNumBits - i - 1);
            final int end = 1 << (kNumBits - i);
            for (int j = start; j < end; j++) {
                ProbPrices[j] = (i << Encoder.kNumBitPriceShiftBits) +
                        (((end - j) << Encoder.kNumBitPriceShiftBits) >>> (kNumBits - i - 1));
            }
        }
    }

    static public int GetPrice(int Prob, int symbol) {
        return ProbPrices[(((Prob - symbol) ^ ((-symbol))) & (RangeBase.kBitModelTotal - 1)) >>> kNumMoveReducingBits];
    }

    static public int GetPrice0(int Prob) {
        return ProbPrices[Prob >>> kNumMoveReducingBits];
    }

    static public int GetPrice1(int Prob) {
        return ProbPrices[(RangeBase.kBitModelTotal - Prob) >>> kNumMoveReducingBits];
    }
}
