// SevenZip/CRC.java

package SevenZip;

public class CRC {
    public static final int[] Table = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i;
            for (int j = 0; j < 8; j++) {
                if ((r & 1) == 0) {
                    r >>>= 1;
                } else {
                    r = (r >>> 1) ^ 0xEDB88320;
                }
            }
            Table[i] = r;
        }
    }

    private int _value = -1;

    public void Init() {
        _value = -1;
    }

    public void Update(byte[] data, int offset, int size) {
        for (int i = 0; i < size; i++) {
            _value = Table[(_value ^ data[offset + i]) & 0xFF] ^ (_value >>> 8);
        }
    }

    public void Update(byte[] data) {
        for (byte aByte : data) {
            _value = Table[(_value ^ aByte) & 0xFF] ^ (_value >>> 8);
        }
    }

    public void UpdateByte(int b) {
        _value = Table[(_value ^ b) & 0xFF] ^ (_value >>> 8);
    }

    public int GetDigest() {
        return _value ^ (-1);
    }
}
