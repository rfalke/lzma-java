package SevenZip.Compression.LZMA;

import SevenZip.Compression.RangeCoder.Encoder;

import java.io.IOException;

class LenPriceTableEncoder extends LenEncoder {
    private final int[] _prices = new int[Base.kNumLenSymbols << Base.kNumPosStatesBitsEncodingMax];
    private final int[] _counters = new int[Base.kNumPosStatesEncodingMax];
    private int _tableSize;

    protected void SetTableSize(int tableSize) {
        _tableSize = tableSize;
    }

    protected int GetPrice(int symbol, int posState) {
        return _prices[posState * Base.kNumLenSymbols + symbol];
    }

    void UpdateTable(int posState) {
        SetPrices(posState, _tableSize, _prices, posState * Base.kNumLenSymbols);
        _counters[posState] = _tableSize;
    }

    protected void UpdateTables(int numPosStates) {
        for (int posState = 0; posState < numPosStates; posState++) {
            UpdateTable(posState);
        }
    }

    @Override
    public void Encode(Encoder rangeEncoder, int symbol, int posState) throws IOException {
        super.Encode(rangeEncoder, symbol, posState);
        if (--_counters[posState] == 0) {
            UpdateTable(posState);
        }
    }
}
