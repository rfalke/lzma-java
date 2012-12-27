package SevenZip.Compression.RangeCoder;

public class ProbPrices {
    public static final int kNumMoveReducingBits = 2;
    static final int[] ProbPrices = new int[RangeBase.kBitModelTotal >>> kNumMoveReducingBits];
    public static final int kNumBitPriceShiftBits = 6;

    static {
        final int kNumBits = (RangeBase.kNumBitModelTotalBits - kNumMoveReducingBits);
        for (int i = kNumBits - 1; i >= 0; i--) {
            final int start = 1 << (kNumBits - i - 1);
            final int end = 1 << (kNumBits - i);
            System.out.println(start+" "+end+" "+i);
            for (int j = start; j < end; j++) {
                ProbPrices[j] = (i << kNumBitPriceShiftBits) +
                        (((end - j) << kNumBitPriceShiftBits) >>> (kNumBits - i - 1));
            }
        }
    }

    static public int getPrice(int prob, int symbol) {
        final int mask = RangeBase.kBitModelTotal - 1;
        final int a = prob - symbol;
        final int b = -symbol;
        final int c = a ^ b;
        return ProbPrices[(c & mask) >>> kNumMoveReducingBits];
    }

    static public int GetPrice0(int Prob) {
        return ProbPrices[Prob >>> kNumMoveReducingBits];
    }

    static public int GetPrice1(int Prob) {
        return ProbPrices[(RangeBase.kBitModelTotal - Prob) >>> kNumMoveReducingBits];
    }
}
