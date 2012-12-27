package SevenZip.Compression.LZMA;

import SevenZip.Compression.RangeCoder.BitTreeEncoder;
import SevenZip.Compression.RangeCoder.Encoder;
import SevenZip.Compression.RangeCoder.ProbPrices;
import SevenZip.Compression.RangeCoder.RangeBase;

import java.io.IOException;

class LenEncoder {
    private final short[] _choice = new short[2];
    private final BitTreeEncoder[] _lowCoder = new BitTreeEncoder[Base.kNumPosStatesEncodingMax];
    private final BitTreeEncoder[] _midCoder = new BitTreeEncoder[Base.kNumPosStatesEncodingMax];
    private final BitTreeEncoder _highCoder = new BitTreeEncoder(Base.kNumHighLenBits);

    protected LenEncoder() {
        for (int posState = 0; posState < Base.kNumPosStatesEncodingMax; posState++) {
            _lowCoder[posState] = new BitTreeEncoder(Base.kNumLowLenBits);
            _midCoder[posState] = new BitTreeEncoder(Base.kNumMidLenBits);
        }
    }

    protected void Init(int numPosStates) {
        RangeBase.InitBitModels(_choice);

        for (int posState = 0; posState < numPosStates; posState++) {
            _lowCoder[posState].Init();
            _midCoder[posState].Init();
        }
        _highCoder.Init();
    }

    protected void Encode(Encoder rangeEncoder, int symbol, int posState) throws IOException {
        if (symbol < Base.kNumLowLenSymbols) {
            rangeEncoder.encode(_choice, 0, 0);
            _lowCoder[posState].Encode(rangeEncoder, symbol);
        } else {
            symbol -= Base.kNumLowLenSymbols;
            rangeEncoder.encode(_choice, 0, 1);
            if (symbol < Base.kNumMidLenSymbols) {
                rangeEncoder.encode(_choice, 1, 0);
                _midCoder[posState].Encode(rangeEncoder, symbol);
            } else {
                rangeEncoder.encode(_choice, 1, 1);
                _highCoder.Encode(rangeEncoder, symbol - Base.kNumMidLenSymbols);
            }
        }
    }

    protected void SetPrices(int posState, int numSymbols, int[] prices, int st) {
        final int a0 = ProbPrices.GetPrice0(_choice[0]);
        final int a1 = ProbPrices.GetPrice1(_choice[0]);
        final int b0 = a1 + ProbPrices.GetPrice0(_choice[1]);
        final int b1 = a1 + ProbPrices.GetPrice1(_choice[1]);
        int i;
        for (i = 0; i < Base.kNumLowLenSymbols; i++) {
            if (i >= numSymbols) {
                return;
            }
            prices[st + i] = a0 + _lowCoder[posState].getPrice(i);
        }
        for (; i < Base.kNumLowLenSymbols + Base.kNumMidLenSymbols; i++) {
            if (i >= numSymbols) {
                return;
            }
            prices[st + i] = b0 + _midCoder[posState].getPrice(i - Base.kNumLowLenSymbols);
        }
        for (; i < numSymbols; i++) {
            prices[st + i] = b1 + _highCoder.getPrice(i - Base.kNumLowLenSymbols - Base.kNumMidLenSymbols);
        }
    }
}
