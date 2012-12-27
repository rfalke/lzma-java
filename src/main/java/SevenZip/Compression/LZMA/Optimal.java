package SevenZip.Compression.LZMA;

class Optimal {
    int State;

    boolean Prev1IsChar;
    boolean Prev2;

    int PosPrev2;
    int BackPrev2;

    int Price;
    int PosPrev;
    int BackPrev;

    int Backs0;
    int Backs1;
    int Backs2;
    int Backs3;

    void MakeAsChar() {
        BackPrev = -1;
        Prev1IsChar = false;
    }

    void MakeAsShortRep() {
        BackPrev = 0;
        Prev1IsChar = false;
    }

    boolean isShortRep() {
        return (BackPrev == 0);
    }
}
