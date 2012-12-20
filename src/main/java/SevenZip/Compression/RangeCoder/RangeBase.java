package SevenZip.Compression.RangeCoder;

public class RangeBase {
    public static final int kNumBitModelTotalBits = 11;
    public static final int kBitModelTotal = (1 << kNumBitModelTotalBits);
    protected static final int kTopMask = ~((1 << 24) - 1);
    protected static final int kNumMoveBits = 5;

    public static void InitBitModels(short[] probs) {
        for (int i = 0; i < probs.length; i++) {
            probs[i] = (kBitModelTotal >>> 1);
        }
    }
}
