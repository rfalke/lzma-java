package SevenZip.Compression.LZMA;

import SevenZip.Compression.LZ.OutWindow;
import SevenZip.Compression.RangeCoder.BitTreeDecoder;
import SevenZip.Compression.RangeCoder.RangeBase;
import SevenZip.Compression.RangeCoder.RangeDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Decoder {
    private static int ReverseDecode(short[] Models, int startIndex, RangeDecoder rangeDecoder, int NumBitLevels) throws IOException {
        int m = 1;
        int symbol = 0;
        for (int bitIndex = 0; bitIndex < NumBitLevels; bitIndex++) {
            final int bit = rangeDecoder.DecodeBit(Models, startIndex + m);
            m <<= 1;
            m += bit;
            symbol |= (bit << bitIndex);
        }
        return symbol;
    }

    static class LenDecoder {
        final short[] m_Choice = new short[2];
        final BitTreeDecoder[] m_LowCoder = new BitTreeDecoder[Base.kNumPosStatesMax];
        final BitTreeDecoder[] m_MidCoder = new BitTreeDecoder[Base.kNumPosStatesMax];
        final BitTreeDecoder m_HighCoder = new BitTreeDecoder(Base.kNumHighLenBits);
        int m_NumPosStates = 0;

        protected void Create(int numPosStates) {
            for (; m_NumPosStates < numPosStates; m_NumPosStates++) {
                m_LowCoder[m_NumPosStates] = new BitTreeDecoder(Base.kNumLowLenBits);
                m_MidCoder[m_NumPosStates] = new BitTreeDecoder(Base.kNumMidLenBits);
            }
        }

        protected void Init() {
            RangeBase.InitBitModels(m_Choice);
            for (int posState = 0; posState < m_NumPosStates; posState++) {
                m_LowCoder[posState].Init();
                m_MidCoder[posState].Init();
            }
            m_HighCoder.Init();
        }

        protected int Decode(RangeDecoder rangeDecoder, int posState) throws IOException {
            if (rangeDecoder.DecodeBit(m_Choice, 0) == 0) {
                return m_LowCoder[posState].Decode(rangeDecoder);
            }
            int symbol = Base.kNumLowLenSymbols;
            if (rangeDecoder.DecodeBit(m_Choice, 1) == 0) {
                symbol += m_MidCoder[posState].Decode(rangeDecoder);
            } else {
                symbol += Base.kNumMidLenSymbols + m_HighCoder.Decode(rangeDecoder);
            }
            return symbol;
        }
    }

    class LiteralDecoder {
        class Decoder2 {
            final short[] m_Decoders = new short[0x300];

            public void Init() {
                RangeBase.InitBitModels(m_Decoders);
            }

            protected byte DecodeNormal(RangeDecoder rangeDecoder) throws IOException {
                int symbol = 1;
                do {
                    symbol = (symbol << 1) | rangeDecoder.DecodeBit(m_Decoders, symbol);
                }
                while (symbol < 0x100);
                return (byte) symbol;
            }

            protected byte DecodeWithMatchByte(RangeDecoder rangeDecoder, byte matchByte) throws IOException {
                int symbol = 1;
                do {
                    final int matchBit = (matchByte >> 7) & 1;
                    matchByte <<= 1;
                    final int bit = rangeDecoder.DecodeBit(m_Decoders, ((1 + matchBit) << 8) + symbol);
                    symbol = (symbol << 1) | bit;
                    if (matchBit != bit) {
                        while (symbol < 0x100) {
                            symbol = (symbol << 1) | rangeDecoder.DecodeBit(m_Decoders, symbol);
                        }
                        break;
                    }
                }
                while (symbol < 0x100);
                return (byte) symbol;
            }
        }

        Decoder2[] m_Coders;
        int m_NumPrevBits;
        int m_NumPosBits;
        int m_PosMask;

        protected void Create(int numPosBits, int numPrevBits) {
            if (m_Coders != null && m_NumPrevBits == numPrevBits && m_NumPosBits == numPosBits) {
                return;
            }
            m_NumPosBits = numPosBits;
            m_PosMask = (1 << numPosBits) - 1;
            m_NumPrevBits = numPrevBits;
            final int numStates = 1 << (m_NumPrevBits + m_NumPosBits);
            m_Coders = new Decoder2[numStates];
            for (int i = 0; i < numStates; i++) {
                m_Coders[i] = new Decoder2();
            }
        }

        protected void Init() {
            final int numStates = 1 << (m_NumPrevBits + m_NumPosBits);
            for (int i = 0; i < numStates; i++) {
                m_Coders[i].Init();
            }
        }

        Decoder2 GetDecoder(int pos, byte prevByte) {
            return m_Coders[((pos & m_PosMask) << m_NumPrevBits) + ((prevByte & 0xFF) >>> (8 - m_NumPrevBits))];
        }
    }

    private final OutWindow m_OutWindow = new OutWindow();
    private final RangeDecoder m_RangeDecoder = new RangeDecoder();

    private final short[] m_IsMatchDecoders = new short[Base.kNumStates << Base.kNumPosStatesBitsMax];
    private final short[] m_IsRepDecoders = new short[Base.kNumStates];
    private final short[] m_IsRepG0Decoders = new short[Base.kNumStates];
    private final short[] m_IsRepG1Decoders = new short[Base.kNumStates];
    private final short[] m_IsRepG2Decoders = new short[Base.kNumStates];
    private final short[] m_IsRep0LongDecoders = new short[Base.kNumStates << Base.kNumPosStatesBitsMax];

    private final BitTreeDecoder[] m_PosSlotDecoder = new BitTreeDecoder[Base.kNumLenToPosStates];
    private final short[] m_PosDecoders = new short[Base.kNumFullDistances - Base.kEndPosModelIndex];

    private final BitTreeDecoder m_PosAlignDecoder = new BitTreeDecoder(Base.kNumAlignBits);

    private final LenDecoder m_LenDecoder = new LenDecoder();
    private final LenDecoder m_RepLenDecoder = new LenDecoder();

    private final LiteralDecoder m_LiteralDecoder = new LiteralDecoder();

    private int m_DictionarySize = -1;
    private int m_DictionarySizeCheck = -1;

    private int m_PosStateMask;

    public Decoder() {
        for (int i = 0; i < Base.kNumLenToPosStates; i++) {
            m_PosSlotDecoder[i] = new BitTreeDecoder(Base.kNumPosSlotBits);
        }
    }

    boolean SetDictionarySize(int dictionarySize) {
        if (dictionarySize < 0) {
            return false;
        }
        if (m_DictionarySize != dictionarySize) {
            m_DictionarySize = dictionarySize;
            m_DictionarySizeCheck = Math.max(m_DictionarySize, 1);
            m_OutWindow.Create(Math.max(m_DictionarySizeCheck, (1 << 12)));
        }
        return true;
    }

    boolean SetLcLpPb(int lc, int lp, int pb) {
        if (lc > Base.kNumLitContextBitsMax || lp > 4 || pb > Base.kNumPosStatesBitsMax) {
            return false;
        }
        m_LiteralDecoder.Create(lp, lc);
        final int numPosStates = 1 << pb;
        m_LenDecoder.Create(numPosStates);
        m_RepLenDecoder.Create(numPosStates);
        m_PosStateMask = numPosStates - 1;
        return true;
    }

    void Init() throws IOException {
        m_OutWindow.Init(false);

        RangeBase.InitBitModels(m_IsMatchDecoders);
        RangeBase.InitBitModels(m_IsRep0LongDecoders);
        RangeBase.InitBitModels(m_IsRepDecoders);
        RangeBase.InitBitModels(m_IsRepG0Decoders);
        RangeBase.InitBitModels(m_IsRepG1Decoders);
        RangeBase.InitBitModels(m_IsRepG2Decoders);
        RangeBase.InitBitModels(m_PosDecoders);

        m_LiteralDecoder.Init();
        for (int i = 0; i < Base.kNumLenToPosStates; i++) {
            m_PosSlotDecoder[i].Init();
        }
        m_LenDecoder.Init();
        m_RepLenDecoder.Init();
        m_PosAlignDecoder.Init();
        m_RangeDecoder.Init();
    }

    public boolean Code(InputStream inStream, OutputStream outStream,
                        long outSize) throws IOException {
        m_RangeDecoder.SetStream(inStream);
        m_OutWindow.SetStream(outStream);
        Init();

        int state = Base.getInitialState();
        int rep0 = 0;
        int rep1 = 0;
        int rep2 = 0;
        int rep3 = 0;

        long nowPos64 = 0;
        byte prevByte = 0;
        while (outSize < 0 || nowPos64 < outSize) {
            final int posState = (int) nowPos64 & m_PosStateMask;
            if (m_RangeDecoder.DecodeBit(m_IsMatchDecoders, (state << Base.kNumPosStatesBitsMax) + posState) == 0) {
                final LiteralDecoder.Decoder2 decoder2 = m_LiteralDecoder.GetDecoder((int) nowPos64, prevByte);
                if (Base.isStateOneWhereAtLastACharWasFound(state)) {
                    prevByte = decoder2.DecodeNormal(m_RangeDecoder);
                } else {
                    prevByte = decoder2.DecodeWithMatchByte(m_RangeDecoder, m_OutWindow.GetByte(rep0));
                }
                m_OutWindow.PutByte(prevByte);
                state = Base.getNextStateAfterLiteralByte(state);
                nowPos64++;
            } else {
                int len;
                if (m_RangeDecoder.DecodeBit(m_IsRepDecoders, state) == 1) {
                    len = 0;
                    if (m_RangeDecoder.DecodeBit(m_IsRepG0Decoders, state) == 0) {
                        if (m_RangeDecoder.DecodeBit(m_IsRep0LongDecoders, (state << Base.kNumPosStatesBitsMax) + posState) == 0) {
                            state = Base.getNextStateAfterShortRep(state);
                            len = 1;
                        }
                    } else {
                        final int distance;
                        if (m_RangeDecoder.DecodeBit(m_IsRepG1Decoders, state) == 0) {
                            distance = rep1;
                        } else {
                            if (m_RangeDecoder.DecodeBit(m_IsRepG2Decoders, state) == 0) {
                                distance = rep2;
                            } else {
                                distance = rep3;
                                rep3 = rep2;
                            }
                            rep2 = rep1;
                        }
                        rep1 = rep0;
                        rep0 = distance;
                    }
                    if (len == 0) {
                        len = m_RepLenDecoder.Decode(m_RangeDecoder, posState) + Base.kMatchMinLen;
                        state = Base.getNextStateAfterLongRep(state);
                    }
                } else {
                    rep3 = rep2;
                    rep2 = rep1;
                    rep1 = rep0;
                    len = Base.kMatchMinLen + m_LenDecoder.Decode(m_RangeDecoder, posState);
                    state = Base.getNextStateAfterMatch(state);
                    final int posSlot = m_PosSlotDecoder[Base.GetLenToPosState(len)].Decode(m_RangeDecoder);
                    if (posSlot >= Base.kStartPosModelIndex) {
                        final int numDirectBits = (posSlot >> 1) - 1;
                        rep0 = ((2 | (posSlot & 1)) << numDirectBits);
                        if (posSlot < Base.kEndPosModelIndex) {
                            rep0 += ReverseDecode(m_PosDecoders,
                                    rep0 - posSlot - 1, m_RangeDecoder, numDirectBits);
                        } else {
                            rep0 += (m_RangeDecoder.DecodeDirectBits(
                                    numDirectBits - Base.kNumAlignBits) << Base.kNumAlignBits);
                            rep0 += m_PosAlignDecoder.ReverseDecode(m_RangeDecoder);
                            if (rep0 < 0) {
                                if (rep0 == -1) {
                                    break;
                                }
                                return false;
                            }
                        }
                    } else {
                        rep0 = posSlot;
                    }
                }
                if (rep0 >= nowPos64 || rep0 >= m_DictionarySizeCheck) {
                    // m_OutWindow.Flush();
                    return false;
                }
                m_OutWindow.CopyBlock(rep0, len);
                nowPos64 += len;
                prevByte = m_OutWindow.GetByte(0);
            }
        }
        m_OutWindow.Flush();
        m_OutWindow.ReleaseStream();
        m_RangeDecoder.ReleaseStream();
        return true;
    }

    public boolean SetDecoderProperties(byte... properties) {
        if (properties.length < 5) {
            return false;
        }
        final int val = properties[0] & 0xFF;
        final int lc = val % 9;
        final int remainder = val / 9;
        final int lp = remainder % 5;
        final int pb = remainder / 5;
        int dictionarySize = 0;
        for (int i = 0; i < 4; i++) {
            final int a = properties[1 + i] & 0xFF;
            dictionarySize += a << (i * 8);
        }
        return SetLcLpPb(lc, lp, pb) && SetDictionarySize(dictionarySize);
    }
}
