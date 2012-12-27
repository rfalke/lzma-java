package SevenZip.Compression.RangeCoder;

import java.io.IOException;

public class BitTreeEncoder {
    private final short[] probs;
    private final int NumBitLevels;

    public BitTreeEncoder(int numBitLevels) {
        NumBitLevels = numBitLevels;
        probs = new short[1 << numBitLevels];
    }

    public void Init() {
        Decoder.InitBitModels(probs);
    }

    public void Encode(Encoder rangeEncoder, int symbol) throws IOException {
        int m = 1;
        for (int bitIndex = NumBitLevels; bitIndex != 0; ) {
            bitIndex--;
            final int bit = (symbol >>> bitIndex) & 1;
            rangeEncoder.encode(probs, m, bit);
            m = (m << 1) | bit;
        }
    }

    public void ReverseEncode(Encoder rangeEncoder, int symbol) throws IOException {
        int m = 1;
        for (int i = 0; i < NumBitLevels; i++) {
            final int bit = symbol & 1;
            rangeEncoder.encode(probs, m, bit);
            m = (m << 1) | bit;
            symbol >>= 1;
        }
    }

    public int getPrice(int symbol) {
        int price = 0;
        int m = 1;
        for (int bitIndex = NumBitLevels; bitIndex != 0; ) {
            bitIndex--;
            final int bit = (symbol >>> bitIndex) & 1;
            price += ProbPrices.getPrice(probs[m], bit);
            m = (m << 1) + bit;
        }
        return price;
    }

    public int ReverseGetPrice(int symbol) {
        int price = 0;
        int m = 1;
        for (int i = NumBitLevels; i != 0; i--) {
            final int bit = symbol & 1;
            symbol >>>= 1;
            price += ProbPrices.getPrice(probs[m], bit);
            m = (m << 1) | bit;
        }
        return price;
    }

}
