package SevenZip.Compression.LZMA;

import SevenZip.Compression.RangeCoder.Encoder;
import SevenZip.Compression.RangeCoder.ProbPrices;
import SevenZip.Compression.RangeCoder.RangeBase;

import java.io.IOException;

class LiteralEncoder {
    class Encoder2 {
        final short[] m_Encoders = new short[0x300];

        protected void Init() {
            RangeBase.InitBitModels(m_Encoders);
        }

        protected void Encode(Encoder rangeEncoder, byte symbol) throws IOException {
            int context = 1;
            for (int i = 7; i >= 0; i--) {
                final int bit = ((symbol >> i) & 1);
                rangeEncoder.Encode(m_Encoders, context, bit);
                context = (context << 1) | bit;
            }
        }

        protected void EncodeMatched(Encoder rangeEncoder, byte matchByte, byte symbol) throws IOException {
            int context = 1;
            boolean same = true;
            for (int i = 7; i >= 0; i--) {
                final int bit = ((symbol >> i) & 1);
                int state = context;
                if (same) {
                    final int matchBit = ((matchByte >> i) & 1);
                    state += ((1 + matchBit) << 8);
                    same = (matchBit == bit);
                }
                rangeEncoder.Encode(m_Encoders, state, bit);
                context = (context << 1) | bit;
            }
        }

        protected int GetPrice(boolean matchMode, byte matchByte, byte symbol) {
            int price = 0;
            int context = 1;
            int i = 7;
            if (matchMode) {
                for (; i >= 0; i--) {
                    final int matchBit = (matchByte >> i) & 1;
                    final int bit = (symbol >> i) & 1;
                    price += ProbPrices.GetPrice(m_Encoders[((1 + matchBit) << 8) + context], bit);
                    context = (context << 1) | bit;
                    if (matchBit != bit) {
                        i--;
                        break;
                    }
                }
            }
            for (; i >= 0; i--) {
                final int bit = (symbol >> i) & 1;
                price += ProbPrices.GetPrice(m_Encoders[context], bit);
                context = (context << 1) | bit;
            }
            return price;
        }
    }

    private Encoder2[] m_Coders;
    private int m_NumPrevBits;
    private int m_NumPosBits;
    private int m_PosMask;

    protected void Create(int numPosBits, int numPrevBits) {
        if (m_Coders != null && m_NumPrevBits == numPrevBits && m_NumPosBits == numPosBits) {
            return;
        }
        m_NumPosBits = numPosBits;
        m_PosMask = (1 << numPosBits) - 1;
        m_NumPrevBits = numPrevBits;
        final int numStates = 1 << (m_NumPrevBits + m_NumPosBits);
        m_Coders = new Encoder2[numStates];
        for (int i = 0; i < numStates; i++) {
            m_Coders[i] = new Encoder2();
        }
    }

    protected void Init() {
        final int numStates = 1 << (m_NumPrevBits + m_NumPosBits);
        for (int i = 0; i < numStates; i++) {
            m_Coders[i].Init();
        }
    }

    protected Encoder2 GetSubCoder(int pos, byte prevByte) {
        return m_Coders[((pos & m_PosMask) << m_NumPrevBits) + ((prevByte & 0xFF) >>> (8 - m_NumPrevBits))];
    }
}
