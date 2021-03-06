package transfer;

/**
 * 可变长度字节缓冲
 * Created by Jake on 2015/2/23.
 */
public class ByteBuffer implements Outputable {

    static final int EXPAND_STEP_SIZE = 256;

    private final ByteArr rootByteArray;

    ByteArr curByteArray;

    private int length;

    public ByteBuffer() {
        this(EXPAND_STEP_SIZE);
    }

    public ByteBuffer(int initLen) {
        this.rootByteArray = new ByteArr(initLen);
        this.curByteArray = this.rootByteArray;
    }

    @Override
    public void putByte(byte byte1) {
        this.curByteArray.checkBounds(this)
                .putByte(byte1);
        length++;
    }


    @Override
    public void putBytes(byte[] bytes) {
        this.curByteArray.checkBounds(bytes.length, this)
                .putBytes(bytes);
        length += bytes.length;
    }

    @Override
    public void putBytes(byte[] bytes, int start, int length) {
        this.curByteArray.checkBounds(length, this)
                .putBytes(bytes, start, length);
        this.length += length;
    }


    /**
     * 获取字节
     * @return
     */
    public ByteArray getByteArray() {
        if (rootByteArray == curByteArray) {
            return new ByteArray(rootByteArray.byteArray, 0, length);
        }

        byte[] byteArray = new byte[length];
        ByteArr curBytesArr = this.rootByteArray;
        int loopOffset = 0;
        do {
            System.arraycopy(curBytesArr.byteArray, 0, byteArray, loopOffset, curBytesArr.offset);
            loopOffset += curBytesArr.offset;
        } while ((curBytesArr = curBytesArr.next) != null);

        return new ByteArray(byteArray, 0, length);
    }


    /**
     * 获取字节数组
     * @return
     */
    public byte[] toBytes() {
        byte[] byteArray = new byte[length];
        if (rootByteArray == curByteArray) {
            System.arraycopy(rootByteArray.byteArray, 0, byteArray, 0, length);
            return byteArray;
        }

        ByteArr curBytesArr = this.rootByteArray;
        int loopOffset = 0;
        do {
            System.arraycopy(curBytesArr.byteArray, 0, byteArray, loopOffset, curBytesArr.offset);
            loopOffset += curBytesArr.offset;
        } while ((curBytesArr = curBytesArr.next) != null);

        return byteArray;
    }


    /**
     * 长度
     * @return
     */
    public int length() {
        return this.length;
    }


}
