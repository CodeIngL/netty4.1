/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 *
 * <p>
 * 解码器，用于在线路结尾处分割接收到的{@link ByteBuf}s。
 * <p>
 * 处理{@code "\n"}和{@code "\r\n"} 两者。
 * <p>
 * 字节流应采用UTF-8字符编码或ASCII格式。
 * 该类实现直接使用{@code byte}进行 {@code char}转换，
 * 然后将该{@code char}与一些低范围ASCII字符（如{@code '\n'}或{@code '\r'}）进行比较。
 * UTF-8不使用低范围[0..0x7F]字节值进行多字节代码点表示，因此完全支持此实现。
 * <p>
 * 有关更通用的基于分隔符的解码器，请参阅{@link DelimiterBasedFrameDecoder}
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /**
     * Maximum length of a frame we're willing to decode.
     * <p>
     *     我们愿意解码的帧的最大长度。
     * </p>
     */
    private final int maxLength;
    /**
     * Whether or not to throw an exception as soon as we exceed maxLength.
     */
    private final boolean failFast;
    private final boolean stripDelimiter;

    /**
     * True if we're discarding input because we're already over maxLength.
     * <p>
     *     true:如果因为我们已经超过maxLength而丢弃输入。
     * </p>
     */
    private boolean discarding;
    private int discardedBytes;

    /**
     * Last scan position.
     */
    private int offset;

    /**
     * Creates a new decoder.
     *
     * @param maxLength the maximum length of the decoded frame.
     *                  A {@link TooLongFrameException} is thrown if
     *                  the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     *
     * @param maxLength      the maximum length of the decoded frame.
     *                       A {@link TooLongFrameException} is thrown if
     *                       the length of the frame exceeds this value.
     * @param stripDelimiter whether the decoded frame should strip out the
     *                       delimiter or not
     * @param failFast       If <tt>true</tt>, a {@link TooLongFrameException} is
     *                       thrown as soon as the decoder notices the length of the
     *                       frame will exceed <tt>maxFrameLength</tt> regardless of
     *                       whether the entire frame has been read.
     *                       If <tt>false</tt>, a {@link TooLongFrameException} is
     *                       thrown after the entire frame that exceeds
     *                       <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param ctx    the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param buffer the {@link ByteBuf} from which to read data
     * @return frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     * be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        final int eol = findEndOfLine(buffer);
        if (!discarding) {
            if (eol >= 0) {
                final ByteBuf frame;
                final int length = eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r' ? 2 : 1;

                if (length > maxLength) {
                    buffer.readerIndex(eol + delimLength);
                    fail(ctx, length);
                    return null;
                }

                if (stripDelimiter) {
                    frame = buffer.readRetainedSlice(length);
                    buffer.skipBytes(delimLength);
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame;
            } else {
                final int length = buffer.readableBytes();
                if (length > maxLength) {
                    discardedBytes = length;
                    buffer.readerIndex(buffer.writerIndex());
                    discarding = true;
                    offset = 0;
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
        } else {
            if (eol >= 0) {
                final int length = discardedBytes + eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r' ? 2 : 1;
                buffer.readerIndex(eol + delimLength);
                discardedBytes = 0;
                discarding = false;
                if (!failFast) {
                    fail(ctx, length);
                }
            } else {
                discardedBytes += buffer.readableBytes();
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     *
     * 返回找到的行尾的缓冲区中的index。 如果在缓冲区中找不到行尾，则返回-1。
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        if (i >= 0) {
            offset = 0;
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        } else {
            offset = totalLength;
        }
        return i;
    }
}
