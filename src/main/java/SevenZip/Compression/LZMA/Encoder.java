package SevenZip.Compression.LZMA;

import SevenZip.Compression.LZ.BinTree;
import SevenZip.Compression.RangeCoder.BitTreeEncoder;
import SevenZip.Compression.RangeCoder.ProbPrices;
import SevenZip.Compression.RangeCoder.RangeBase;
import SevenZip.ICodeProgress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Encoder {
    private static final int EMatchFinderTypeBT2 = 0;
    private static final int EMatchFinderTypeBT4 = 1;
    private static final int kNumOpts = 1 << 12;
    private static final int kPropSize = 5;

    private static final int kIfinityPrice = 0xFFFFFFF;

    private static final byte[] g_FastPos = new byte[1 << 11];

    private static final int kDefaultDictionaryLogSize = 22;
    private static final int kNumFastBytesDefault = 0x20;

    static {
        final int kFastSlots = 22;
        int c = 2;
        g_FastPos[0] = 0;
        g_FastPos[1] = 1;
        for (int slotFast = 2; slotFast < kFastSlots; slotFast++) {
            final int k = (1 << ((slotFast >> 1) - 1));
            for (int j = 0; j < k; j++, c++) {
                g_FastPos[c] = (byte) slotFast;
            }
        }
    }

    private static int GetPosSlot(int pos) {
        if (pos < (1 << 11)) {
            return g_FastPos[pos];
        }
        if (pos < (1 << 21)) {
            return (g_FastPos[pos >> 10] + 20);
        }
        return (g_FastPos[pos >> 20] + 40);
    }

    private static int GetPosSlot2(int pos) {
        if (pos < (1 << 17)) {
            return (g_FastPos[pos >> 6] + 12);
        }
        if (pos < (1 << 27)) {
            return (g_FastPos[pos >> 16] + 32);
        }
        return (g_FastPos[pos >> 26] + 52);
    }

    private final SevenZip.Compression.RangeCoder.Encoder _rangeEncoder = new SevenZip.Compression.RangeCoder.Encoder();

    private final byte[] properties = new byte[kPropSize];
    private final int[] tempPrices = new int[Base.kNumFullDistances];

    private final Optimal[] _optimum = new Optimal[kNumOpts];

    private final short[] _isMatch = new short[Base.kNumStates << Base.kNumPosStatesBitsMax];
    private final short[] _isRep = new short[Base.kNumStates];
    private final short[] _isRepG0 = new short[Base.kNumStates];
    private final short[] _isRepG1 = new short[Base.kNumStates];
    private final short[] _isRepG2 = new short[Base.kNumStates];
    private final short[] _isRep0Long = new short[Base.kNumStates << Base.kNumPosStatesBitsMax];

    private final BitTreeEncoder[] _posSlotEncoder = new BitTreeEncoder[Base.kNumLenToPosStates]; // kNumPosSlotBits

    private final short[] _posEncoders = new short[Base.kNumFullDistances - Base.kEndPosModelIndex];
    private final BitTreeEncoder _posAlignEncoder = new BitTreeEncoder(Base.kNumAlignBits);

    private final LenPriceTableEncoder _lenEncoder = new LenPriceTableEncoder();
    private final LenPriceTableEncoder _repMatchLenEncoder = new LenPriceTableEncoder();

    private final LiteralEncoder _literalEncoder = new LiteralEncoder();

    private final int[] _matchDistances = new int[Base.kMatchMaxLen * 2 + 2];

    private int _matchPriceCount;
    private BinTree _matchFinder = null;

    private int _numFastBytes = kNumFastBytesDefault;
    private int _longestMatchLength;
    private int _numDistancePairs;

    private int _additionalOffset;

    private int _optimumEndIndex;
    private int _optimumCurrentIndex;

    private boolean _longestMatchWasFound;

    private final int[] _posSlotPrices = new int[1 << (Base.kNumPosSlotBits + Base.kNumLenToPosStatesBits)];
    private final int[] _distancesPrices = new int[Base.kNumFullDistances << Base.kNumLenToPosStatesBits];
    private final int[] _alignPrices = new int[Base.kAlignTableSize];
    private int _alignPriceCount;

    private int _distTableSize = (kDefaultDictionaryLogSize * 2);

    private int _posStateBits = 2;
    private int _posStateMask = (4 - 1);
    private int _numLiteralPosStateBits = 0;
    private int _numLiteralContextBits = 3;

    private int _dictionarySize = (1 << kDefaultDictionaryLogSize);
    private int _dictionarySizePrev = -1;
    private int _numFastBytesPrev = -1;

    private static class HlContext {
        private long processedInSize;
        private long processedOutSize;
        private boolean finished;
        private boolean thereIsStillWork;
        private long nowPos64;
    }

    private InputStream _inStream;

    private int _matchFinderType = EMatchFinderTypeBT4;
    private boolean _shouldWriteEndMarker = false;

    private boolean _needReleaseMFStream = false;

    private int _state = Base.getInitialState();
    private byte _previousByte;
    private final int[] _repDistances = new int[Base.kNumRepDistances];
    private final int[] reps = new int[Base.kNumRepDistances];
    private final int[] repLens = new int[Base.kNumRepDistances];
    private int backRes;

    private static int ReverseGetPrice(short[] Models, int startIndex,
                                       int NumBitLevels, int symbol) {
        int price = 0;
        int m = 1;
        for (int i = NumBitLevels; i != 0; i--) {
            final int bit = symbol & 1;
            symbol >>>= 1;
            price += ProbPrices.GetPrice(Models[startIndex + m], bit);
            m = (m << 1) | bit;
        }
        return price;
    }

    private static void ReverseEncode(short[] Models, int startIndex,
                                      SevenZip.Compression.RangeCoder.Encoder rangeEncoder, int NumBitLevels, int symbol) throws IOException {
        int m = 1;
        for (int i = 0; i < NumBitLevels; i++) {
            final int bit = symbol & 1;
            rangeEncoder.Encode(Models, startIndex + m, bit);
            m = (m << 1) | bit;
            symbol >>= 1;
        }
    }

    public Encoder() {
        for (int i = 0; i < kNumOpts; i++) {
            _optimum[i] = new Optimal();
        }
        for (int i = 0; i < Base.kNumLenToPosStates; i++) {
            _posSlotEncoder[i] = new BitTreeEncoder(Base.kNumPosSlotBits);
        }
    }

    void BaseInit() {
        _state = Base.getInitialState();
        _previousByte = 0;
        for (int i = 0; i < Base.kNumRepDistances; i++) {
            _repDistances[i] = 0;
        }
    }

    void Create() {
        if (_matchFinder == null) {
            final BinTree bt = new BinTree();
            int numHashBytes = 4;
            if (_matchFinderType == EMatchFinderTypeBT2) {
                numHashBytes = 2;
            }
            bt.SetType(numHashBytes);
            _matchFinder = bt;
        }
        _literalEncoder.Create(_numLiteralPosStateBits, _numLiteralContextBits);

        if (_dictionarySize == _dictionarySizePrev && _numFastBytesPrev == _numFastBytes) {
            return;
        }
        _matchFinder.Create(_dictionarySize, kNumOpts, _numFastBytes, Base.kMatchMaxLen + 1);
        _dictionarySizePrev = _dictionarySize;
        _numFastBytesPrev = _numFastBytes;
    }

    void SetWriteEndMarkerMode(boolean writeEndMarker) {
        _shouldWriteEndMarker = writeEndMarker;
    }

    void Init() {
        BaseInit();
        _rangeEncoder.Init();

        RangeBase.InitBitModels(_isMatch);
        RangeBase.InitBitModels(_isRep0Long);
        RangeBase.InitBitModels(_isRep);
        RangeBase.InitBitModels(_isRepG0);
        RangeBase.InitBitModels(_isRepG1);
        RangeBase.InitBitModels(_isRepG2);
        RangeBase.InitBitModels(_posEncoders);


        _literalEncoder.Init();
        for (int i = 0; i < Base.kNumLenToPosStates; i++) {
            _posSlotEncoder[i].Init();
        }


        _lenEncoder.Init(1 << _posStateBits);
        _repMatchLenEncoder.Init(1 << _posStateBits);

        _posAlignEncoder.Init();

        _longestMatchWasFound = false;
        _optimumEndIndex = 0;
        _optimumCurrentIndex = 0;
        _additionalOffset = 0;
    }

    int ReadMatchDistances() throws IOException {
        int lenRes = 0;
        _numDistancePairs = _matchFinder.GetMatches(_matchDistances);
        if (_numDistancePairs > 0) {
            lenRes = _matchDistances[_numDistancePairs - 2];
            if (lenRes == _numFastBytes) {
                lenRes += _matchFinder.GetMatchLen((int) lenRes - 1, _matchDistances[_numDistancePairs - 1],
                        Base.kMatchMaxLen - lenRes);
            }
        }
        _additionalOffset++;
        return lenRes;
    }

    void MovePos(int num) throws IOException {
        if (num > 0) {
            _matchFinder.Skip(num);
            _additionalOffset += num;
        }
    }

    int GetRepLen1Price(int state, int posState) {
        return ProbPrices.GetPrice0(_isRepG0[state]) +
                ProbPrices.GetPrice0(_isRep0Long[(state << Base.kNumPosStatesBitsMax) + posState]);
    }

    int GetPureRepPrice(int repIndex, int state, int posState) {
        int price;
        if (repIndex == 0) {
            price = ProbPrices.GetPrice0(_isRepG0[state]);
            price += ProbPrices.GetPrice1(_isRep0Long[(state << Base.kNumPosStatesBitsMax) + posState]);
        } else {
            price = ProbPrices.GetPrice1(_isRepG0[state]);
            if (repIndex == 1) {
                price += ProbPrices.GetPrice0(_isRepG1[state]);
            } else {
                price += ProbPrices.GetPrice1(_isRepG1[state]);
                price += ProbPrices.GetPrice(_isRepG2[state], repIndex - 2);
            }
        }
        return price;
    }

    int GetRepPrice(int repIndex, int len, int state, int posState) {
        final int price = _repMatchLenEncoder.GetPrice(len - Base.kMatchMinLen, posState);
        return price + GetPureRepPrice(repIndex, state, posState);
    }

    int GetPosLenPrice(int pos, int len, int posState) {
        final int price;
        final int lenToPosState = Base.GetLenToPosState(len);
        if (pos < Base.kNumFullDistances) {
            price = _distancesPrices[(lenToPosState * Base.kNumFullDistances) + pos];
        } else {
            price = _posSlotPrices[(lenToPosState << Base.kNumPosSlotBits) + GetPosSlot2(pos)] +
                    _alignPrices[pos & Base.kAlignMask];
        }
        return price + _lenEncoder.GetPrice(len - Base.kMatchMinLen, posState);
    }

    int Backward(int cur) {
        _optimumEndIndex = cur;
        int posMem = _optimum[cur].PosPrev;
        int backMem = _optimum[cur].BackPrev;
        do {
            if (_optimum[cur].Prev1IsChar) {
                _optimum[posMem].MakeAsChar();
                _optimum[posMem].PosPrev = posMem - 1;
                if (_optimum[cur].Prev2) {
                    _optimum[posMem - 1].Prev1IsChar = false;
                    _optimum[posMem - 1].PosPrev = _optimum[cur].PosPrev2;
                    _optimum[posMem - 1].BackPrev = _optimum[cur].BackPrev2;
                }
            }
            final int posPrev = posMem;
            final int backCur = backMem;

            backMem = _optimum[posPrev].BackPrev;
            posMem = _optimum[posPrev].PosPrev;

            _optimum[posPrev].BackPrev = backCur;
            _optimum[posPrev].PosPrev = cur;
            cur = posPrev;
        }
        while (cur > 0);
        backRes = _optimum[0].BackPrev;
        _optimumCurrentIndex = _optimum[0].PosPrev;
        return _optimumCurrentIndex;
    }

    int GetOptimum(int position) throws IOException {
        if (_optimumEndIndex != _optimumCurrentIndex) {
            final int lenRes = _optimum[_optimumCurrentIndex].PosPrev - _optimumCurrentIndex;
            backRes = _optimum[_optimumCurrentIndex].BackPrev;
            _optimumCurrentIndex = _optimum[_optimumCurrentIndex].PosPrev;
            return lenRes;
        }
        _optimumCurrentIndex = _optimumEndIndex = 0;

        final int lenMain;
        int numDistancePairs;
        if (!_longestMatchWasFound) {
            lenMain = ReadMatchDistances();
        } else {
            lenMain = _longestMatchLength;
            _longestMatchWasFound = false;
        }
        numDistancePairs = _numDistancePairs;

        int numAvailableBytes = _matchFinder.GetNumAvailableBytes() + 1;
        if (numAvailableBytes < 2) {
            backRes = -1;
            return 1;
        }
        if (numAvailableBytes > Base.kMatchMaxLen) {
            numAvailableBytes = Base.kMatchMaxLen;
        }

        int repMaxIndex = 0;
        int i;
        for (i = 0; i < Base.kNumRepDistances; i++) {
            reps[i] = _repDistances[i];
            repLens[i] = _matchFinder.GetMatchLen(0 - 1, reps[i], Base.kMatchMaxLen);
            if (repLens[i] > repLens[repMaxIndex]) {
                repMaxIndex = i;
            }
        }
        if (repLens[repMaxIndex] >= _numFastBytes) {
            backRes = repMaxIndex;
            final int lenRes = repLens[repMaxIndex];
            MovePos(lenRes - 1);
            return lenRes;
        }

        if (lenMain >= _numFastBytes) {
            backRes = _matchDistances[numDistancePairs - 1] + Base.kNumRepDistances;
            MovePos(lenMain - 1);
            return lenMain;
        }

        byte currentByte = _matchFinder.GetIndexByte(0 - 1);
        byte matchByte = _matchFinder.GetIndexByte(0 - _repDistances[0] - 1 - 1);

        if (lenMain < 2 && currentByte != matchByte && repLens[repMaxIndex] < 2) {
            backRes = -1;
            return 1;
        }

        _optimum[0].State = _state;

        int posState = (position & _posStateMask);

        _optimum[1].Price = ProbPrices.GetPrice0(_isMatch[(_state << Base.kNumPosStatesBitsMax) + posState]) +
                _literalEncoder.GetSubCoder(position, _previousByte).GetPrice(!Base.isStateOneWhereAtLastACharWasFound(_state), matchByte, currentByte);
        _optimum[1].MakeAsChar();

        int matchPrice = ProbPrices.GetPrice1(_isMatch[(_state << Base.kNumPosStatesBitsMax) + posState]);
        int repMatchPrice = matchPrice + ProbPrices.GetPrice1(_isRep[_state]);

        if (matchByte == currentByte) {
            final int shortRepPrice = repMatchPrice + GetRepLen1Price(_state, posState);
            if (shortRepPrice < _optimum[1].Price) {
                _optimum[1].Price = shortRepPrice;
                _optimum[1].MakeAsShortRep();
            }
        }

        int lenEnd = ((lenMain >= repLens[repMaxIndex]) ? lenMain : repLens[repMaxIndex]);

        if (lenEnd < 2) {
            backRes = _optimum[1].BackPrev;
            return 1;
        }

        _optimum[1].PosPrev = 0;

        _optimum[0].Backs0 = reps[0];
        _optimum[0].Backs1 = reps[1];
        _optimum[0].Backs2 = reps[2];
        _optimum[0].Backs3 = reps[3];

        int len = lenEnd;
        do {
            _optimum[len--].Price = kIfinityPrice;
        }
        while (len >= 2);

        for (i = 0; i < Base.kNumRepDistances; i++) {
            int repLen = repLens[i];
            if (repLen < 2) {
                continue;
            }
            final int price = repMatchPrice + GetPureRepPrice(i, _state, posState);
            do {
                final int curAndLenPrice = price + _repMatchLenEncoder.GetPrice(repLen - 2, posState);
                final Optimal optimum = _optimum[repLen];
                if (curAndLenPrice < optimum.Price) {
                    optimum.Price = curAndLenPrice;
                    optimum.PosPrev = 0;
                    optimum.BackPrev = i;
                    optimum.Prev1IsChar = false;
                }
            }
            while (--repLen >= 2);
        }

        int normalMatchPrice = matchPrice + ProbPrices.GetPrice0(_isRep[_state]);

        len = ((repLens[0] >= 2) ? repLens[0] + 1 : 2);
        if (len <= lenMain) {
            int offs = 0;
            while (len > _matchDistances[offs]) {
                offs += 2;
            }
            for (; ; len++) {
                final int distance = _matchDistances[offs + 1];
                final int curAndLenPrice = normalMatchPrice + GetPosLenPrice(distance, len, posState);
                final Optimal optimum = _optimum[len];
                if (curAndLenPrice < optimum.Price) {
                    optimum.Price = curAndLenPrice;
                    optimum.PosPrev = 0;
                    optimum.BackPrev = distance + Base.kNumRepDistances;
                    optimum.Prev1IsChar = false;
                }
                if (len == _matchDistances[offs]) {
                    offs += 2;
                    if (offs == numDistancePairs) {
                        break;
                    }
                }
            }
        }

        int cur = 0;

        while (true) {
            cur++;
            if (cur == lenEnd) {
                return Backward(cur);
            }
            int newLen = ReadMatchDistances();
            numDistancePairs = _numDistancePairs;
            if (newLen >= _numFastBytes) {

                _longestMatchLength = newLen;
                _longestMatchWasFound = true;
                return Backward(cur);
            }
            position++;
            int posPrev = _optimum[cur].PosPrev;
            int state;
            if (_optimum[cur].Prev1IsChar) {
                posPrev--;
                if (_optimum[cur].Prev2) {
                    state = _optimum[_optimum[cur].PosPrev2].State;
                    if (_optimum[cur].BackPrev2 < Base.kNumRepDistances) {
                        state = Base.getNextStateAfterRep(state);
                    } else {
                        state = Base.getNextStateAfterMatch(state);
                    }
                } else {
                    state = _optimum[posPrev].State;
                }
                state = Base.getNextStateAfterChar(state);
            } else {
                state = _optimum[posPrev].State;
            }
            if (posPrev == cur - 1) {
                if (_optimum[cur].IsShortRep()) {
                    state = Base.getNextStateAfterShortRep(state);
                } else {
                    state = Base.getNextStateAfterChar(state);
                }
            } else {
                final int pos;
                if (_optimum[cur].Prev1IsChar && _optimum[cur].Prev2) {
                    posPrev = _optimum[cur].PosPrev2;
                    pos = _optimum[cur].BackPrev2;
                    state = Base.getNextStateAfterRep(state);
                } else {
                    pos = _optimum[cur].BackPrev;
                    if (pos < Base.kNumRepDistances) {
                        state = Base.getNextStateAfterRep(state);
                    } else {
                        state = Base.getNextStateAfterMatch(state);
                    }
                }
                final Optimal opt = _optimum[posPrev];
                if (pos < Base.kNumRepDistances) {
                    if (pos == 0) {
                        reps[0] = opt.Backs0;
                        reps[1] = opt.Backs1;
                        reps[2] = opt.Backs2;
                        reps[3] = opt.Backs3;
                    } else if (pos == 1) {
                        reps[0] = opt.Backs1;
                        reps[1] = opt.Backs0;
                        reps[2] = opt.Backs2;
                        reps[3] = opt.Backs3;
                    } else if (pos == 2) {
                        reps[0] = opt.Backs2;
                        reps[1] = opt.Backs0;
                        reps[2] = opt.Backs1;
                        reps[3] = opt.Backs3;
                    } else {
                        reps[0] = opt.Backs3;
                        reps[1] = opt.Backs0;
                        reps[2] = opt.Backs1;
                        reps[3] = opt.Backs2;
                    }
                } else {
                    reps[0] = (pos - Base.kNumRepDistances);
                    reps[1] = opt.Backs0;
                    reps[2] = opt.Backs1;
                    reps[3] = opt.Backs2;
                }
            }
            _optimum[cur].State = state;
            _optimum[cur].Backs0 = reps[0];
            _optimum[cur].Backs1 = reps[1];
            _optimum[cur].Backs2 = reps[2];
            _optimum[cur].Backs3 = reps[3];
            final int curPrice = _optimum[cur].Price;

            currentByte = _matchFinder.GetIndexByte(0 - 1);
            matchByte = _matchFinder.GetIndexByte(0 - reps[0] - 1 - 1);

            posState = (position & _posStateMask);

            final int curAnd1Price = curPrice +
                    ProbPrices.GetPrice0(_isMatch[(state << Base.kNumPosStatesBitsMax) + posState]) +
                    _literalEncoder.GetSubCoder(position, _matchFinder.GetIndexByte(0 - 2)).
                            GetPrice(!Base.isStateOneWhereAtLastACharWasFound(state), matchByte, currentByte);

            final Optimal nextOptimum = _optimum[cur + 1];

            boolean nextIsChar = false;
            if (curAnd1Price < nextOptimum.Price) {
                nextOptimum.Price = curAnd1Price;
                nextOptimum.PosPrev = cur;
                nextOptimum.MakeAsChar();
                nextIsChar = true;
            }

            matchPrice = curPrice + ProbPrices.GetPrice1(_isMatch[(state << Base.kNumPosStatesBitsMax) + posState]);
            repMatchPrice = matchPrice + ProbPrices.GetPrice1(_isRep[state]);

            if (matchByte == currentByte &&
                    !(nextOptimum.PosPrev < cur && nextOptimum.BackPrev == 0)) {
                final int shortRepPrice = repMatchPrice + GetRepLen1Price(state, posState);
                if (shortRepPrice <= nextOptimum.Price) {
                    nextOptimum.Price = shortRepPrice;
                    nextOptimum.PosPrev = cur;
                    nextOptimum.MakeAsShortRep();
                    nextIsChar = true;
                }
            }

            int numAvailableBytesFull = _matchFinder.GetNumAvailableBytes() + 1;
            numAvailableBytesFull = Math.min(kNumOpts - 1 - cur, numAvailableBytesFull);
            numAvailableBytes = numAvailableBytesFull;

            if (numAvailableBytes < 2) {
                continue;
            }
            if (numAvailableBytes > _numFastBytes) {
                numAvailableBytes = _numFastBytes;
            }
            if (!nextIsChar && matchByte != currentByte) {
                // try Literal + rep0
                final int t = Math.min(numAvailableBytesFull - 1, _numFastBytes);
                final int lenTest2 = _matchFinder.GetMatchLen(0, reps[0], t);
                if (lenTest2 >= 2) {
                    final int state2 = Base.getNextStateAfterChar(state);

                    final int posStateNext = (position + 1) & _posStateMask;
                    final int nextRepMatchPrice = curAnd1Price +
                            ProbPrices.GetPrice1(_isMatch[(state2 << Base.kNumPosStatesBitsMax) + posStateNext]) +
                            ProbPrices.GetPrice1(_isRep[state2]);
                    {
                        final int offset = cur + 1 + lenTest2;
                        while (lenEnd < offset) {
                            _optimum[++lenEnd].Price = kIfinityPrice;
                        }
                        final int curAndLenPrice = nextRepMatchPrice + GetRepPrice(
                                0, lenTest2, state2, posStateNext);
                        final Optimal optimum = _optimum[offset];
                        if (curAndLenPrice < optimum.Price) {
                            optimum.Price = curAndLenPrice;
                            optimum.PosPrev = cur + 1;
                            optimum.BackPrev = 0;
                            optimum.Prev1IsChar = true;
                            optimum.Prev2 = false;
                        }
                    }
                }
            }

            int startLen = 2; // speed optimization

            for (int repIndex = 0; repIndex < Base.kNumRepDistances; repIndex++) {
                int lenTest = _matchFinder.GetMatchLen(0 - 1, reps[repIndex], numAvailableBytes);
                if (lenTest < 2) {
                    continue;
                }
                final int lenTestTemp = lenTest;
                do {
                    while (lenEnd < cur + lenTest) {
                        _optimum[++lenEnd].Price = kIfinityPrice;
                    }
                    final int curAndLenPrice = repMatchPrice + GetRepPrice(repIndex, lenTest, state, posState);
                    final Optimal optimum = _optimum[cur + lenTest];
                    if (curAndLenPrice < optimum.Price) {
                        optimum.Price = curAndLenPrice;
                        optimum.PosPrev = cur;
                        optimum.BackPrev = repIndex;
                        optimum.Prev1IsChar = false;
                    }
                }
                while (--lenTest >= 2);
                lenTest = lenTestTemp;

                if (repIndex == 0) {
                    startLen = lenTest + 1;
                }

                // if (_maxMode)
                if (lenTest < numAvailableBytesFull) {
                    final int t = Math.min(numAvailableBytesFull - 1 - lenTest, _numFastBytes);
                    final int lenTest2 = _matchFinder.GetMatchLen(lenTest, reps[repIndex], t);
                    if (lenTest2 >= 2) {
                        int state2 = Base.getNextStateAfterRep(state);

                        int posStateNext = (position + lenTest) & _posStateMask;
                        final int curAndLenCharPrice =
                                repMatchPrice + GetRepPrice(repIndex, lenTest, state, posState) +
                                        ProbPrices.GetPrice0(_isMatch[(state2 << Base.kNumPosStatesBitsMax) + posStateNext]) +
                                        _literalEncoder.GetSubCoder(position + lenTest,
                                                _matchFinder.GetIndexByte(lenTest - 1 - 1)).GetPrice(true,
                                                _matchFinder.GetIndexByte(lenTest - 1 - (reps[repIndex] + 1)),
                                                _matchFinder.GetIndexByte(lenTest - 1));
                        state2 = Base.getNextStateAfterChar(state2);
                        posStateNext = (position + lenTest + 1) & _posStateMask;
                        final int nextMatchPrice = curAndLenCharPrice + ProbPrices.GetPrice1(_isMatch[(state2 << Base.kNumPosStatesBitsMax) + posStateNext]);
                        final int nextRepMatchPrice = nextMatchPrice + ProbPrices.GetPrice1(_isRep[state2]);

                        // for(; lenTest2 >= 2; lenTest2--)
                        {
                            final int offset = lenTest + 1 + lenTest2;
                            while (lenEnd < cur + offset) {
                                _optimum[++lenEnd].Price = kIfinityPrice;
                            }
                            final int curAndLenPrice = nextRepMatchPrice + GetRepPrice(0, lenTest2, state2, posStateNext);
                            final Optimal optimum = _optimum[cur + offset];
                            if (curAndLenPrice < optimum.Price) {
                                optimum.Price = curAndLenPrice;
                                optimum.PosPrev = cur + lenTest + 1;
                                optimum.BackPrev = 0;
                                optimum.Prev1IsChar = true;
                                optimum.Prev2 = true;
                                optimum.PosPrev2 = cur;
                                optimum.BackPrev2 = repIndex;
                            }
                        }
                    }
                }
            }

            if (newLen > numAvailableBytes) {
                newLen = numAvailableBytes;
                for (numDistancePairs = 0; newLen > _matchDistances[numDistancePairs]; numDistancePairs += 2) {
                }
                _matchDistances[numDistancePairs] = newLen;
                numDistancePairs += 2;
            }
            if (newLen >= startLen) {
                normalMatchPrice = matchPrice + ProbPrices.GetPrice0(_isRep[state]);
                while (lenEnd < cur + newLen) {
                    _optimum[++lenEnd].Price = kIfinityPrice;
                }

                int offs = 0;
                while (startLen > _matchDistances[offs]) {
                    offs += 2;
                }

                for (int lenTest = startLen; ; lenTest++) {
                    final int curBack = _matchDistances[offs + 1];
                    int curAndLenPrice = normalMatchPrice + GetPosLenPrice(curBack, lenTest, posState);
                    Optimal optimum = _optimum[cur + lenTest];
                    if (curAndLenPrice < optimum.Price) {
                        optimum.Price = curAndLenPrice;
                        optimum.PosPrev = cur;
                        optimum.BackPrev = curBack + Base.kNumRepDistances;
                        optimum.Prev1IsChar = false;
                    }

                    if (lenTest == _matchDistances[offs]) {
                        if (lenTest < numAvailableBytesFull) {
                            final int t = Math.min(numAvailableBytesFull - 1 - lenTest, _numFastBytes);
                            final int lenTest2 = _matchFinder.GetMatchLen(lenTest, curBack, t);
                            if (lenTest2 >= 2) {
                                int state2 = Base.getNextStateAfterMatch(state);

                                int posStateNext = (position + lenTest) & _posStateMask;
                                final int curAndLenCharPrice = curAndLenPrice +
                                        ProbPrices.GetPrice0(_isMatch[(state2 << Base.kNumPosStatesBitsMax) + posStateNext]) +
                                        _literalEncoder.GetSubCoder(position + lenTest,
                                                _matchFinder.GetIndexByte(lenTest - 1 - 1)).
                                                GetPrice(true,
                                                        _matchFinder.GetIndexByte(lenTest - (curBack + 1) - 1),
                                                        _matchFinder.GetIndexByte(lenTest - 1));
                                state2 = Base.getNextStateAfterChar(state2);
                                posStateNext = (position + lenTest + 1) & _posStateMask;
                                final int nextMatchPrice = curAndLenCharPrice + ProbPrices.GetPrice1(_isMatch[(state2 << Base.kNumPosStatesBitsMax) + posStateNext]);
                                final int nextRepMatchPrice = nextMatchPrice + ProbPrices.GetPrice1(_isRep[state2]);

                                final int offset = lenTest + 1 + lenTest2;
                                while (lenEnd < cur + offset) {
                                    _optimum[++lenEnd].Price = kIfinityPrice;
                                }
                                curAndLenPrice = nextRepMatchPrice + GetRepPrice(0, lenTest2, state2, posStateNext);
                                optimum = _optimum[cur + offset];
                                if (curAndLenPrice < optimum.Price) {
                                    optimum.Price = curAndLenPrice;
                                    optimum.PosPrev = cur + lenTest + 1;
                                    optimum.BackPrev = 0;
                                    optimum.Prev1IsChar = true;
                                    optimum.Prev2 = true;
                                    optimum.PosPrev2 = cur;
                                    optimum.BackPrev2 = curBack + Base.kNumRepDistances;
                                }
                            }
                        }
                        offs += 2;
                        if (offs == numDistancePairs) {
                            break;
                        }
                    }
                }
            }
        }
    }

    boolean ChangePair(int smallDist, int bigDist) {
        final int kDif = 7;
        return (smallDist < (1 << (32 - kDif)) && bigDist >= (smallDist << kDif));
    }

    void WriteEndMarker(int posState) throws IOException {
        if (!_shouldWriteEndMarker) {
            return;
        }

        _rangeEncoder.Encode(_isMatch, (_state << Base.kNumPosStatesBitsMax) + posState, 1);
        _rangeEncoder.Encode(_isRep, _state, 0);
        _state = Base.getNextStateAfterMatch(_state);
        final int len = Base.kMatchMinLen;
        _lenEncoder.Encode(_rangeEncoder, len - Base.kMatchMinLen, posState);
        final int posSlot = (1 << Base.kNumPosSlotBits) - 1;
        final int lenToPosState = Base.GetLenToPosState(len);
        _posSlotEncoder[lenToPosState].Encode(_rangeEncoder, posSlot);
        final int footerBits = 30;
        final int posReduced = (1 << footerBits) - 1;
        _rangeEncoder.EncodeDirectBits(posReduced >> Base.kNumAlignBits, footerBits - Base.kNumAlignBits);
        _posAlignEncoder.ReverseEncode(_rangeEncoder, posReduced & Base.kAlignMask);
    }

    void Flush(int nowPos) throws IOException {
        ReleaseMFStream();
        WriteEndMarker(nowPos & _posStateMask);
        _rangeEncoder.FlushData();
        _rangeEncoder.FlushStream();
    }

    protected boolean CodeOneBlock(HlContext hlContext) throws IOException {
        hlContext.processedInSize = 0;
        hlContext.processedOutSize = 0;

        if (_inStream != null) {
            _matchFinder.SetStream(_inStream);
            _matchFinder.Init();
            _needReleaseMFStream = true;
            _inStream = null;
        }

        if (hlContext.finished) {
            return false;
        }

        final long progressPosValuePrev = hlContext.nowPos64;
        final boolean firstCall = hlContext.nowPos64 == 0;
        if (firstCall) {
            if (_matchFinder.GetNumAvailableBytes() == 0) {
                Flush((int) hlContext.nowPos64);
                return false;
            }

            ReadMatchDistances();
            final int posState = (int) (hlContext.nowPos64) & _posStateMask;
            _rangeEncoder.Encode(_isMatch, (_state << Base.kNumPosStatesBitsMax) + posState, 0);
            _state = Base.getNextStateAfterChar(_state);
            final byte curByte = _matchFinder.GetIndexByte(0 - _additionalOffset);
            _literalEncoder.GetSubCoder((int) (hlContext.nowPos64), _previousByte).Encode(_rangeEncoder, curByte);
            _previousByte = curByte;
            _additionalOffset--;
            hlContext.nowPos64++;
        }
        if (_matchFinder.GetNumAvailableBytes() == 0) {
            Flush((int) hlContext.nowPos64);
            return false;
        }

        hlContext.thereIsStillWork = false;
        while (encodeOne(hlContext, progressPosValuePrev)) {
        }
        return hlContext.thereIsStillWork;
    }

    private boolean encodeOne(HlContext hlContext, long progressPosValuePrev) throws IOException {
        final int len = GetOptimum((int) hlContext.nowPos64);
        int pos = backRes;
        final int posState = ((int) hlContext.nowPos64) & _posStateMask;
        final int complexState = (_state << Base.kNumPosStatesBitsMax) + posState;
        if (len == 1 && pos == -1) {
            _rangeEncoder.Encode(_isMatch, complexState, 0);
            final byte curByte = _matchFinder.GetIndexByte((int) (0 - _additionalOffset));
            final LiteralEncoder.Encoder2 subCoder = _literalEncoder.GetSubCoder((int) hlContext.nowPos64, _previousByte);
            if (Base.isStateOneWhereAtLastACharWasFound(_state)) {
                subCoder.Encode(_rangeEncoder, curByte);
            } else {
                final byte matchByte = _matchFinder.GetIndexByte((int) (0 - _repDistances[0] - 1 - _additionalOffset));
                subCoder.EncodeMatched(_rangeEncoder, matchByte, curByte);
            }
            _previousByte = curByte;
            _state = Base.getNextStateAfterChar(_state);
        } else {
            _rangeEncoder.Encode(_isMatch, complexState, 1);
            if (pos < Base.kNumRepDistances) {
                _rangeEncoder.Encode(_isRep, _state, 1);
                if (pos == 0) {
                    _rangeEncoder.Encode(_isRepG0, _state, 0);
                    if (len == 1) {
                        _rangeEncoder.Encode(_isRep0Long, complexState, 0);
                    } else {
                        _rangeEncoder.Encode(_isRep0Long, complexState, 1);
                    }
                } else {
                    _rangeEncoder.Encode(_isRepG0, _state, 1);
                    if (pos == 1) {
                        _rangeEncoder.Encode(_isRepG1, _state, 0);
                    } else {
                        _rangeEncoder.Encode(_isRepG1, _state, 1);
                        _rangeEncoder.Encode(_isRepG2, _state, pos - 2);
                    }
                }
                if (len == 1) {
                    _state = Base.getNextStateAfterShortRep(_state);
                } else {
                    _repMatchLenEncoder.Encode(_rangeEncoder, len - Base.kMatchMinLen, posState);
                    _state = Base.getNextStateAfterRep(_state);
                }
                final int distance = _repDistances[pos];
                if (pos != 0) {
                    for (int i = pos; i >= 1; i--) {
                        _repDistances[i] = _repDistances[i - 1];
                    }
                    _repDistances[0] = distance;
                }
            } else {
                _rangeEncoder.Encode(_isRep, _state, 0);
                _state = Base.getNextStateAfterMatch(_state);
                _lenEncoder.Encode(_rangeEncoder, len - Base.kMatchMinLen, posState);
                pos -= Base.kNumRepDistances;
                final int posSlot = GetPosSlot(pos);
                final int lenToPosState = Base.GetLenToPosState(len);
                _posSlotEncoder[lenToPosState].Encode(_rangeEncoder, posSlot);

                if (posSlot >= Base.kStartPosModelIndex) {
                    final int footerBits = (int) ((posSlot >> 1) - 1);
                    final int baseVal = ((2 | (posSlot & 1)) << footerBits);
                    final int posReduced = pos - baseVal;

                    if (posSlot < Base.kEndPosModelIndex) {
                        ReverseEncode(_posEncoders,
                                baseVal - posSlot - 1, _rangeEncoder, footerBits, posReduced);
                    } else {
                        _rangeEncoder.EncodeDirectBits(posReduced >> Base.kNumAlignBits, footerBits - Base.kNumAlignBits);
                        _posAlignEncoder.ReverseEncode(_rangeEncoder, posReduced & Base.kAlignMask);
                        _alignPriceCount++;
                    }
                }
                final int distance = pos;
                for (int i = Base.kNumRepDistances - 1; i >= 1; i--) {
                    _repDistances[i] = _repDistances[i - 1];
                }
                _repDistances[0] = distance;
                _matchPriceCount++;
            }
            _previousByte = _matchFinder.GetIndexByte(len - 1 - _additionalOffset);
        }
        _additionalOffset -= len;
        hlContext.nowPos64 += len;
        if (_additionalOffset == 0) {
            // if (!_fastMode)
            if (_matchPriceCount >= (1 << 7)) {
                FillDistancesPrices();
            }
            if (_alignPriceCount >= Base.kAlignTableSize) {
                FillAlignPrices();
            }
            hlContext.processedInSize = hlContext.nowPos64;
            hlContext.processedOutSize = _rangeEncoder.GetProcessedSizeAdd();
            if (_matchFinder.GetNumAvailableBytes() == 0) {
                Flush((int) hlContext.nowPos64);
                return false;
            }

            if (hlContext.nowPos64 - progressPosValuePrev >= (1 << 12)) {
                hlContext.finished = false;
                hlContext.thereIsStillWork = true;
                return false;
            }
        }
        return true;
    }

    void ReleaseMFStream() {
        if (_matchFinder != null && _needReleaseMFStream) {
            _matchFinder.ReleaseStream();
            _needReleaseMFStream = false;
        }
    }

    void SetOutStream(OutputStream outStream) {
        _rangeEncoder.SetStream(outStream);
    }

    void ReleaseOutStream() {
        _rangeEncoder.ReleaseStream();
    }

    void ReleaseStreams() {
        ReleaseMFStream();
        ReleaseOutStream();
    }

    void SetStreams(InputStream inStream, OutputStream outStream, long inSize, long outSize) {
        _inStream = inStream;
        Create();
        SetOutStream(outStream);
        Init();

        // if (!_fastMode)
        {
            FillDistancesPrices();
            FillAlignPrices();
        }

        _lenEncoder.SetTableSize(_numFastBytes + 1 - Base.kMatchMinLen);
        _lenEncoder.UpdateTables(1 << _posStateBits);
        _repMatchLenEncoder.SetTableSize(_numFastBytes + 1 - Base.kMatchMinLen);
        _repMatchLenEncoder.UpdateTables(1 << _posStateBits);
    }

    public void Code(InputStream inStream, OutputStream outStream, long inSize, long outSize, ICodeProgress progress) throws IOException {
        _needReleaseMFStream = false;
        final HlContext hlContext = new HlContext();
        try {
            SetStreams(inStream, outStream, inSize, outSize);
            while (CodeOneBlock(hlContext)) {
                if (progress != null) {
                    progress.SetProgress(hlContext.processedInSize, hlContext.processedOutSize);
                }
            }
        } finally {
            ReleaseStreams();
        }
    }

    public void WriteCoderProperties(OutputStream outStream) throws IOException {
        properties[0] = (byte) ((_posStateBits * 5 + _numLiteralPosStateBits) * 9 + _numLiteralContextBits);
        for (int i = 0; i < 4; i++) {
            properties[1 + i] = (byte) (_dictionarySize >> (8 * i));
        }
        outStream.write(properties, 0, kPropSize);
    }

    void FillDistancesPrices() {
        for (int i = Base.kStartPosModelIndex; i < Base.kNumFullDistances; i++) {
            final int posSlot = GetPosSlot(i);
            final int footerBits = (int) ((posSlot >> 1) - 1);
            final int baseVal = ((2 | (posSlot & 1)) << footerBits);
            tempPrices[i] = ReverseGetPrice(_posEncoders,
                    baseVal - posSlot - 1, footerBits, i - baseVal);
        }

        for (int lenToPosState = 0; lenToPosState < Base.kNumLenToPosStates; lenToPosState++) {
            int posSlot;
            final BitTreeEncoder encoder = _posSlotEncoder[lenToPosState];

            final int st = (lenToPosState << Base.kNumPosSlotBits);
            for (posSlot = 0; posSlot < _distTableSize; posSlot++) {
                _posSlotPrices[st + posSlot] = encoder.GetPrice(posSlot);
            }
            for (posSlot = Base.kEndPosModelIndex; posSlot < _distTableSize; posSlot++) {
                _posSlotPrices[st + posSlot] += ((((posSlot >> 1) - 1) - Base.kNumAlignBits) << SevenZip.Compression.RangeCoder.Encoder.kNumBitPriceShiftBits);
            }

            final int st2 = lenToPosState * Base.kNumFullDistances;
            int i;
            for (i = 0; i < Base.kStartPosModelIndex; i++) {
                _distancesPrices[st2 + i] = _posSlotPrices[st + i];
            }
            for (; i < Base.kNumFullDistances; i++) {
                _distancesPrices[st2 + i] = _posSlotPrices[st + GetPosSlot(i)] + tempPrices[i];
            }
        }
        _matchPriceCount = 0;
    }

    void FillAlignPrices() {
        for (int i = 0; i < Base.kAlignTableSize; i++) {
            _alignPrices[i] = _posAlignEncoder.ReverseGetPrice(i);
        }
        _alignPriceCount = 0;
    }

    public boolean SetAlgorithm(int algorithm) {
        /*
          _fastMode = (algorithm == 0);
          _maxMode = (algorithm >= 2);
          */
        return true;
    }

    public boolean SetDictionarySize(int dictionarySize) {
        final int kDicLogSizeMaxCompress = 29;
        if (dictionarySize < (1 << Base.kDicLogSizeMin) || dictionarySize > (1 << kDicLogSizeMaxCompress)) {
            return false;
        }
        _dictionarySize = dictionarySize;
        int dicLogSize;
        for (dicLogSize = 0; dictionarySize > (1 << dicLogSize); dicLogSize++) {
        }
        _distTableSize = dicLogSize * 2;
        return true;
    }

    public boolean SetNumFastBytes(int numFastBytes) {
        if (numFastBytes < 5 || numFastBytes > Base.kMatchMaxLen) {
            return false;
        }
        _numFastBytes = numFastBytes;
        return true;
    }

    public boolean SetMatchFinder(int matchFinderIndex) {
        if (matchFinderIndex < 0 || matchFinderIndex > 2) {
            return false;
        }
        final int matchFinderIndexPrev = _matchFinderType;
        _matchFinderType = matchFinderIndex;
        if (_matchFinder != null && matchFinderIndexPrev != _matchFinderType) {
            _dictionarySizePrev = -1;
            _matchFinder = null;
        }
        return true;
    }

    public boolean SetLcLpPb(int lc, int lp, int pb) {
        if (lp < 0 || lp > Base.kNumLitPosStatesBitsEncodingMax ||
                lc < 0 || lc > Base.kNumLitContextBitsMax ||
                pb < 0 || pb > Base.kNumPosStatesBitsEncodingMax) {
            return false;
        }
        _numLiteralPosStateBits = lp;
        _numLiteralContextBits = lc;
        _posStateBits = pb;
        _posStateMask = ((1) << _posStateBits) - 1;
        return true;
    }

    public void SetEndMarkerMode(boolean endMarkerMode) {
        _shouldWriteEndMarker = endMarkerMode;
    }
}
