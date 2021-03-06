/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.io;

import java.nio.charset.ModifiedUtf8;

/**
 * Wraps an existing {@link InputStream} and reads big-endian typed data from it.
 * Typically, this stream has been written by a DataOutputStream. Types that can
 * be read include byte, 16-bit short, 32-bit int, 32-bit float, 64-bit long,
 * 64-bit double, byte strings, and strings encoded in
 * {@link DataInput modified UTF-8}.
 *
 * @see DataOutputStream
 */
public class DataInputStream extends FilterInputStream implements DataInput {

    private final byte[] buff = new byte[8];

    /**
     * Constructs a new DataInputStream on the InputStream {@code in}. All
     * reads are then filtered through this stream. Note that data read by this
     * stream is not in a human readable format and was most likely created by a
     * DataOutputStream.
     *
     * <p><strong>Warning:</strong> passing a null source creates an invalid
     * {@code DataInputStream}. All operations on such a stream will fail.
     *
     * @param in
     *            the source InputStream the filter reads from.
     * @see DataOutputStream
     * @see RandomAccessFile
     */
    public DataInputStream(InputStream in) {
        super(in);
    }

    /**
     * Reads bytes from this stream into the byte array {@code buffer}. Returns
     * the number of bytes that have been read.
     *
     * @param buffer
     *            the buffer to read bytes into.
     * @return the number of bytes that have been read or -1 if the end of the
     *         stream has been reached.
     * @throws IOException
     *             if a problem occurs while reading from this stream.
     * @see DataOutput#write(byte[])
     * @see DataOutput#write(byte[], int, int)
     */
    @Override
    public final int read(byte[] buffer) throws IOException {
        return in.read(buffer, 0, buffer.length);
    }

    /**
     * Reads at most {@code length} bytes from this stream and stores them in
     * the byte array {@code buffer} starting at {@code offset}. Returns the
     * number of bytes that have been read or -1 if no bytes have been read and
     * the end of the stream has been reached.
     *
     * @param buffer
     *            the byte array in which to store the bytes read.
     * @param offset
     *            the initial position in {@code buffer} to store the bytes
     *            read from this stream.
     * @param length
     *            the maximum number of bytes to store in {@code buffer}.
     * @return the number of bytes that have been read or -1 if the end of the
     *         stream has been reached.
     * @throws IOException
     *             if a problem occurs while reading from this stream.
     * @see DataOutput#write(byte[])
     * @see DataOutput#write(byte[], int, int)
     */
    @Override
    public final int read(byte[] buffer, int offset, int length) throws IOException {
        return in.read(buffer, offset, length);
    }

    public final boolean readBoolean() throws IOException {
        int temp = in.read();
        if (temp < 0) {
            throw new EOFException();
        }
        return temp != 0;
    }

    public final byte readByte() throws IOException {
        int temp = in.read();
        if (temp < 0) {
            throw new EOFException();
        }
        return (byte) temp;
    }

    public final char readChar() throws IOException {
        if (readToBuff(2) < 0){
            throw new EOFException();
        }
        return (char) (((buff[0] & 0xff) << 8) | (buff[1] & 0xff));

    }

    private int readToBuff(int count) throws IOException {
        int offset = 0;
        while (offset < count) {
            int bytesRead = in.read(buff, offset, count - offset);
            if (bytesRead == -1) {
                return bytesRead;
            }
            offset += bytesRead;
        }
        return offset;
    }

    public final double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    public final float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public final void readFully(byte[] buffer) throws IOException {
        readFully(buffer, 0, buffer.length);
    }

    /**
     * Reads bytes from this stream and stores them in the byte array {@code
     * buffer} starting at the position {@code offset}. This method blocks until
     * {@code length} bytes have been read. If {@code length} is zero, then this
     * method returns without reading any bytes.
     *
     * @param buffer
     *            the byte array into which the data is read.
     * @param offset
     *            the offset in {@code buffer} from where to store the bytes
     *            read.
     * @param length
     *            the maximum number of bytes to read.
     * @throws EOFException
     *             if the end of the source stream is reached before enough
     *             bytes have been read.
     * @throws IndexOutOfBoundsException
     *             if {@code offset < 0} or {@code length < 0}, or if {@code
     *             offset + length} is greater than the size of {@code buffer}.
     * @throws IOException
     *             if a problem occurs while reading from this stream.
     * @throws NullPointerException
     *             if {@code buffer} or the source stream are null.
     * @see java.io.DataInput#readFully(byte[], int, int)
     */
    public final void readFully(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return;
        }
        if (in == null) {
            throw new NullPointerException("in == null");
        }
        if (buffer == null) {
            throw new NullPointerException("buffer == null");
        }
        // BEGIN android-changed
        // Exception priorities (in case of multiple errors) differ from
        // RI, but are spec-compliant.
        // used (offset | length) < 0 instead of separate (offset < 0) and
        // (length < 0) check to safe one operation
        if ((offset | length) < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException();
        }
        // END android-changed
        while (length > 0) {
            int result = in.read(buffer, offset, length);
            if (result < 0) {
                throw new EOFException();
            }
            offset += result;
            length -= result;
        }
    }

    public final int readInt() throws IOException {
        if (readToBuff(4) < 0){
            throw new EOFException();
        }
        return ((buff[0] & 0xff) << 24) | ((buff[1] & 0xff) << 16) |
            ((buff[2] & 0xff) << 8) | (buff[3] & 0xff);
    }

    @Deprecated
    public final String readLine() throws IOException {
        StringBuilder line = new StringBuilder(80); // Typical line length
        boolean foundTerminator = false;
        while (true) {
            int nextByte = in.read();
            switch (nextByte) {
                case -1:
                    if (line.length() == 0 && !foundTerminator) {
                        return null;
                    }
                    return line.toString();
                case (byte) '\r':
                    if (foundTerminator) {
                        ((PushbackInputStream) in).unread(nextByte);
                        return line.toString();
                    }
                    foundTerminator = true;
                    /* Have to be able to peek ahead one byte */
                    if (!(in.getClass() == PushbackInputStream.class)) {
                        in = new PushbackInputStream(in);
                    }
                    break;
                case (byte) '\n':
                    return line.toString();
                default:
                    if (foundTerminator) {
                        ((PushbackInputStream) in).unread(nextByte);
                        return line.toString();
                    }
                    line.append((char) nextByte);
            }
        }
    }

    public final long readLong() throws IOException {
        if (readToBuff(8) < 0){
            throw new EOFException();
        }
        int i1 = ((buff[0] & 0xff) << 24) | ((buff[1] & 0xff) << 16) |
            ((buff[2] & 0xff) << 8) | (buff[3] & 0xff);
        int i2 = ((buff[4] & 0xff) << 24) | ((buff[5] & 0xff) << 16) |
            ((buff[6] & 0xff) << 8) | (buff[7] & 0xff);

        return ((i1 & 0xffffffffL) << 32) | (i2 & 0xffffffffL);
    }

    public final short readShort() throws IOException {
        if (readToBuff(2) < 0){
            throw new EOFException();
        }
        return (short) (((buff[0] & 0xff) << 8) | (buff[1] & 0xff));
    }

    public final int readUnsignedByte() throws IOException {
        int temp = in.read();
        if (temp < 0) {
            throw new EOFException();
        }
        return temp;
    }

    public final int readUnsignedShort() throws IOException {
        if (readToBuff(2) < 0){
            throw new EOFException();
        }
        return (char) (((buff[0] & 0xff) << 8) | (buff[1] & 0xff));
    }

    public final String readUTF() throws IOException {
        return decodeUTF(readUnsignedShort());
    }

    String decodeUTF(int utfSize) throws IOException {
        return decodeUTF(utfSize, this);
    }

    private static String decodeUTF(int utfSize, DataInput in) throws IOException {
        byte[] buf = new byte[utfSize];
        in.readFully(buf, 0, utfSize);
        return ModifiedUtf8.decode(buf, new char[utfSize], 0, utfSize);
    }

    public static final String readUTF(DataInput in) throws IOException {
        return decodeUTF(in.readUnsignedShort(), in);
    }

    /**
     * Skips {@code count} number of bytes in this stream. Subsequent {@code
     * read()}s will not return these bytes unless {@code reset()} is used.
     *
     * This method will not throw an {@link EOFException} if the end of the
     * input is reached before {@code count} bytes where skipped.
     *
     * @param count
     *            the number of bytes to skip.
     * @return the number of bytes actually skipped.
     * @throws IOException
     *             if a problem occurs during skipping.
     * @see #mark(int)
     * @see #reset()
     */
    public final int skipBytes(int count) throws IOException {
        int skipped = 0;
        long skip;
        while (skipped < count && (skip = in.skip(count - skipped)) != 0) {
            skipped += skip;
        }
        return skipped;
    }
}
