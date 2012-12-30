// LZ.BinTree

package SevenZip.Compression.LZ;

import SevenZip.CRC;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;


public class BinTree extends InWindow {
    private static final int kHash2Size = 1 << 10;
    private static final int kHash3Size = 1 << 16;
    private static final int kBT2HashSize = 1 << 16;
    private static final int kStartMaxLen = 1;
    private static final int kHash3Offset = kHash2Size;
    private static final int kEmptyHashValue = 0;
    private static final int kMaxValForNormalize = (1 << 30) - 1;
    private static final Logger log = Logger.getLogger(BinTree.class.getName());

    public static class LengthAndDistance {
        public int length;
        public final int distance;


        public LengthAndDistance(int length, int distance) {
            this.length = length;
            this.distance = distance;
        }

        @Override
        public String toString() {
            return "LengthAndDistance{" +
                    "length=" + length +
                    ", distance=" + distance +
                    '}';
        }
    }


    private int _cyclicBufferPos;
    private int _cyclicBufferSize = 0;
    private int _matchMaxLen;

    private int[] _son;
    private int[] _hash;

    private int _cutValue = 0xFF;
    private int _hashMask;
    private int _hashSizeSum = 0;

    private boolean HASH_ARRAY = true;

    private int kNumHashDirectBytes = 0;
    private int kMinMatchCheck = 4;
    private int kFixHashSize = kHash2Size + kHash3Size;

    public void SetType(int numHashBytes) {
        HASH_ARRAY = (numHashBytes > 2);
        if (HASH_ARRAY) {
            kNumHashDirectBytes = 0;
            kMinMatchCheck = 4;
            kFixHashSize = kHash2Size + kHash3Size;
        } else {
            kNumHashDirectBytes = 2;
            kMinMatchCheck = 2 + 1;
            kFixHashSize = 0;
        }
    }

    @Override
    public void Init() throws IOException {
        super.Init();
        for (int i = 0; i < _hashSizeSum; i++) {
            _hash[i] = kEmptyHashValue;
        }
        _cyclicBufferPos = 0;
        reduceOffsets(-1);
    }

    @Override
    protected void incrementPosition() throws IOException {
        if (++_cyclicBufferPos >= _cyclicBufferSize) {
            _cyclicBufferPos = 0;
        }
        super.incrementPosition();
        if (_pos == kMaxValForNormalize) {
            Normalize();
        }
    }

    public void Create(int historySize, int keepAddBufferBefore,
                       int matchMaxLen, int keepAddBufferAfter) {
        if (historySize > kMaxValForNormalize - 256) {
            throw new RuntimeException();
        }
        _cutValue = 16 + (matchMaxLen >> 1);

        final int windowReservSize = (historySize + keepAddBufferBefore +
                matchMaxLen + keepAddBufferAfter) / 2 + 256;

        super.Create(historySize + keepAddBufferBefore, matchMaxLen + keepAddBufferAfter, windowReservSize);

        _matchMaxLen = matchMaxLen;

        final int cyclicBufferSize = historySize + 1;
        if (_cyclicBufferSize != cyclicBufferSize) {
            _cyclicBufferSize = cyclicBufferSize;
            _son = new int[_cyclicBufferSize * 2];
        }

        int hs = kBT2HashSize;

        if (HASH_ARRAY) {
            hs = historySize - 1;
            hs |= (hs >> 1);
            hs |= (hs >> 2);
            hs |= (hs >> 4);
            hs |= (hs >> 8);
            hs >>= 1;
            hs |= 0xFFFF;
            if (hs > (1 << 24)) {
                hs >>= 1;
            }
            _hashMask = hs;
            hs++;
            hs += kFixHashSize;
        }
        if (hs != _hashSizeSum) {
            _hashSizeSum = hs;
            _hash = new int[_hashSizeSum];
        }
    }

    /**
     * @return the number of set array cells
     */
    public int fillMatches(LengthAndDistance[] lengthAndDistances) throws IOException {
        final int oldPosition = _pos;
        final int result = fillMatches0(lengthAndDistances);
        if (log.isLoggable(Level.FINE)) {
            log.fine("  found " + result + " match pairs for offset " + oldPosition + " now " + _pos);
            for (int i = 0; i < result; i++) {
                final LengthAndDistance lengthAndDistance = lengthAndDistances[i];
                log.fine("    [" + i + "]: length=" + lengthAndDistance.length + " distance=" + lengthAndDistance.distance);
            }
        }
        return result;
    }

    private int fillMatches0(LengthAndDistance[] distances) throws IOException {
        final int lenLimit;
        if (_pos + _matchMaxLen <= _streamPos) {
            lenLimit = _matchMaxLen;
        } else {
            lenLimit = _streamPos - _pos;
            if (lenLimit < kMinMatchCheck) {
                incrementPosition();
                return 0;
            }
        }

        final int matchMinPos = (_pos > _cyclicBufferSize) ? (_pos - _cyclicBufferSize) : 0;
        final int cur = _bufferOffset + _pos;
        final int hashValue;
        int hash2Value = 0;
        int hash3Value = 0;

        if (HASH_ARRAY) {
            int temp = CrcTable[_bufferBase[cur] & 0xFF] ^ (_bufferBase[cur + 1] & 0xFF);
            hash2Value = temp & (kHash2Size - 1);
            temp ^= ((_bufferBase[cur + 2] & 0xFF) << 8);
            hash3Value = temp & (kHash3Size - 1);
            hashValue = (temp ^ (CrcTable[_bufferBase[cur + 3] & 0xFF] << 5)) & _hashMask;
        } else {
            hashValue = ((_bufferBase[cur] & 0xFF) ^ ((_bufferBase[cur + 1] & 0xFF) << 8));
        }

        int curMatch = _hash[kFixHashSize + hashValue];
        int maxLen = kStartMaxLen; // to avoid items for len < hashSize;
        int offset = 0;
        if (HASH_ARRAY) {
            int curMatch2 = _hash[hash2Value];
            final int curMatch3 = _hash[kHash3Offset + hash3Value];
            _hash[hash2Value] = _pos;
            _hash[kHash3Offset + hash3Value] = _pos;
            if (curMatch2 > matchMinPos) {
                if (_bufferBase[_bufferOffset + curMatch2] == _bufferBase[cur]) {
                    maxLen = 2;
                    distances[offset++] = new LengthAndDistance(2, _pos - curMatch2 - 1);
                }
            }
            if (curMatch3 > matchMinPos) {
                if (_bufferBase[_bufferOffset + curMatch3] == _bufferBase[cur]) {
                    if (curMatch3 == curMatch2) {
                        offset--;
                    }
                    maxLen = 3;
                    distances[offset++] = new LengthAndDistance(3, _pos - curMatch3 - 1);
                    curMatch2 = curMatch3;
                }
            }
            if (offset != 0 && curMatch2 == curMatch) {
                offset--;
                maxLen = kStartMaxLen;
            }
        }

        _hash[kFixHashSize + hashValue] = _pos;

        int ptr0 = (_cyclicBufferPos << 1) + 1;
        int ptr1 = (_cyclicBufferPos << 1);

        int len0 = kNumHashDirectBytes;
        int len1 = kNumHashDirectBytes;

        if (kNumHashDirectBytes != 0) {
            if (curMatch > matchMinPos) {
                if (_bufferBase[_bufferOffset + curMatch + kNumHashDirectBytes] !=
                        _bufferBase[cur + kNumHashDirectBytes]) {
                    maxLen = kNumHashDirectBytes;
                    distances[offset++] = new LengthAndDistance(kNumHashDirectBytes, _pos - curMatch - 1);
                }
            }
        }

        int count = _cutValue;

        while (true) {
            if (curMatch <= matchMinPos || count-- == 0) {
                _son[ptr0] = kEmptyHashValue;
                _son[ptr1] = kEmptyHashValue;
                break;
            }
            final int delta = _pos - curMatch;
            final int cyclicPos = ((delta <= _cyclicBufferPos) ?
                    (_cyclicBufferPos - delta) :
                    (_cyclicBufferPos - delta + _cyclicBufferSize)) << 1;

            final int pby1 = _bufferOffset + curMatch;
            int len = Math.min(len0, len1);
            if (_bufferBase[pby1 + len] == _bufferBase[cur + len]) {
                while (++len != lenLimit) {
                    if (_bufferBase[pby1 + len] != _bufferBase[cur + len]) {
                        break;
                    }
                }
                if (maxLen < len) {
                    maxLen = len;
                    distances[offset++] = new LengthAndDistance(maxLen, delta - 1);
                    if (len == lenLimit) {
                        _son[ptr1] = _son[cyclicPos];
                        _son[ptr0] = _son[cyclicPos + 1];
                        break;
                    }
                }
            }
            if ((_bufferBase[pby1 + len] & 0xFF) < (_bufferBase[cur + len] & 0xFF)) {
                _son[ptr1] = curMatch;
                ptr1 = cyclicPos + 1;
                curMatch = _son[ptr1];
                len1 = len;
            } else {
                _son[ptr0] = curMatch;
                ptr0 = cyclicPos;
                curMatch = _son[ptr0];
                len0 = len;
            }
        }
        incrementPosition();
        return offset;
    }

    public void Skip(int num) throws IOException {
        do {
            final int lenLimit;
            if (_pos + _matchMaxLen <= _streamPos) {
                lenLimit = _matchMaxLen;
            } else {
                lenLimit = _streamPos - _pos;
                if (lenLimit < kMinMatchCheck) {
                    incrementPosition();
                    continue;
                }
            }

            final int matchMinPos = (_pos > _cyclicBufferSize) ? (_pos - _cyclicBufferSize) : 0;
            final int cur = _bufferOffset + _pos;

            final int hashValue;

            if (HASH_ARRAY) {
                int temp = CrcTable[_bufferBase[cur] & 0xFF] ^ (_bufferBase[cur + 1] & 0xFF);
                final int hash2Value = temp & (kHash2Size - 1);
                _hash[hash2Value] = _pos;
                temp ^= ((_bufferBase[cur + 2] & 0xFF) << 8);
                final int hash3Value = temp & (kHash3Size - 1);
                _hash[kHash3Offset + hash3Value] = _pos;
                hashValue = (temp ^ (CrcTable[_bufferBase[cur + 3] & 0xFF] << 5)) & _hashMask;
            } else {
                hashValue = ((_bufferBase[cur] & 0xFF) ^ ((_bufferBase[cur + 1] & 0xFF) << 8));
            }

            int curMatch = _hash[kFixHashSize + hashValue];
            _hash[kFixHashSize + hashValue] = _pos;

            int ptr0 = (_cyclicBufferPos << 1) + 1;
            int ptr1 = (_cyclicBufferPos << 1);

            int len0 = kNumHashDirectBytes;
            int len1 = kNumHashDirectBytes;

            int count = _cutValue;
            while (true) {
                if (curMatch <= matchMinPos || count-- == 0) {
                    _son[ptr0] = kEmptyHashValue;
                    _son[ptr1] = kEmptyHashValue;
                    break;
                }

                final int delta = _pos - curMatch;
                final int cyclicPos = ((delta <= _cyclicBufferPos) ?
                        (_cyclicBufferPos - delta) :
                        (_cyclicBufferPos - delta + _cyclicBufferSize)) << 1;

                final int pby1 = _bufferOffset + curMatch;
                int len = Math.min(len0, len1);
                if (_bufferBase[pby1 + len] == _bufferBase[cur + len]) {
                    while (++len != lenLimit) {
                        if (_bufferBase[pby1 + len] != _bufferBase[cur + len]) {
                            break;
                        }
                    }
                    if (len == lenLimit) {
                        _son[ptr1] = _son[cyclicPos];
                        _son[ptr0] = _son[cyclicPos + 1];
                        break;
                    }
                }
                if ((_bufferBase[pby1 + len] & 0xFF) < (_bufferBase[cur + len] & 0xFF)) {
                    _son[ptr1] = curMatch;
                    ptr1 = cyclicPos + 1;
                    curMatch = _son[ptr1];
                    len1 = len;
                } else {
                    _son[ptr0] = curMatch;
                    ptr0 = cyclicPos;
                    curMatch = _son[ptr0];
                    len0 = len;
                }
            }
            incrementPosition();
        }
        while (--num != 0);
    }

    static void NormalizeLinks(int[] items, int numItems, int subValue) {
        for (int i = 0; i < numItems; i++) {
            int value = items[i];
            if (value <= subValue) {
                value = kEmptyHashValue;
            } else {
                value -= subValue;
            }
            items[i] = value;
        }
    }

    void Normalize() {
        final int subValue = _pos - _cyclicBufferSize;
        NormalizeLinks(_son, _cyclicBufferSize * 2, subValue);
        NormalizeLinks(_hash, _hashSizeSum, subValue);
        reduceOffsets(subValue);
    }

    public void SetCutValue(int cutValue) {
        _cutValue = cutValue;
    }

    private static final int[] CrcTable = CRC.Table;
}
