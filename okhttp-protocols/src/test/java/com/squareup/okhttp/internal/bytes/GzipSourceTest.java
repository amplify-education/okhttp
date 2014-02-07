/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal.bytes;

import com.squareup.okhttp.internal.Util;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class GzipSourceTest {

  @Test public void gunzip() throws Exception {
    GzipOptions options = GzipOptions.NONE;
    checkPermitOptions(options);
  }

  @Test public void gunzip_withName() throws Exception {
    checkPermitOptions(new GzipOptions().name("foo.txt"));
  }

  @Test public void gunzip_withComment() throws Exception {
    checkPermitOptions(new GzipOptions().comment("rubbish"));
  }

  @Test public void gunzip_withExtra() throws Exception {
    checkPermitOptions(new GzipOptions().extra("blubber".getBytes()));
  }

  /**
   * For portability, it is a good idea to export the gzipped bytes and try running gzip.  Ex.
   * {@code echo gzipped | base64 --decode | gzip -l -v}
   */
  @Test public void gunzip_withAll() throws Exception {
    checkPermitOptions(new GzipOptions()
        .name("foo.txt")
        .comment("rubbish")
        .extra("blubber".getBytes()));
  }

  private void checkPermitOptions(GzipOptions options) throws IOException {
    String original = "It's a UNIX system! I know this!";
    OkBuffer gzipped = gzip(buffer(original), options);
    OkBuffer gunzipped = gunzip(gzipped);
    assertEquals(original, gunzipped.readUtf8((int) gunzipped.byteCount()));
  }

  /**
   * Note that you cannot test this with old versions of gzip, as they
   * interpret flag bit 1 as CONTINUATION, not HCRC. For example, this
   * is the case with the default gzip on osx.
   */
  @Test public void gunzipWhenHeaderCRCIncorrect() throws Exception {
    String original = "It's a UNIX system! I know this!";

    CRC32 hcrc = new CRC32();
    hcrc.update(0x1f); // magic1
    hcrc.update(0x8b); // magic2
    hcrc.update(8); // deflated
    hcrc.update(2); // flag HCRC
    hcrc.update(new byte[6]); // rest is zero
    short expectedHcrc = (short) hcrc.getValue();

    // Check our preconditions.
    OkBuffer gzipped = gzip(buffer(original), new GzipOptions().headerCrc32(expectedHcrc));
    gzipped.skip(10);
    assertEquals(0x00261d, gzipped.readShortLe());

    // Set an incorrect Header CRC-32.
    gzipped = gzip(buffer(original), new GzipOptions().headerCrc32((short) 0));
    try {
      gunzip(gzipped);
      fail();
    } catch (IOException e) {
      assertEquals("FHCRC: actual 0x00261d != expected 0x000000", e.getMessage());
    }
  }

  @Test public void gunzipWhenCRCIncorrect() throws Exception {
    String original = "It's a UNIX system! I know this!";

    // Check our preconditions, crc-32 is 0x37ad8f8d.
    OkBuffer gzipped = gzip(buffer(original), GzipOptions.NONE);
    gzipped.skip(gzipped.byteCount() - 8);
    assertEquals(0x37ad8f8d, gzipped.readIntLe());

    // Set an incorrect CRC-32.
    gzipped = gzip(buffer(original), new GzipOptions().crc32(0x01234567));
    try {
      gunzip(gzipped);
      fail();
    } catch (IOException e) {
      assertEquals("CRC: actual 0x37ad8f8d != expected 0x1234567", e.getMessage());
    }
  }

  @Test public void gunzipWhenLengthIncorrect() throws Exception {
    String original = "It's a UNIX system! I know this!";

    // Check our preconditions, length is 0x37ad8f8d.
    OkBuffer gzipped = gzip(buffer(original), GzipOptions.NONE);
    gzipped.skip(gzipped.byteCount() - 4);
    assertEquals(0x000020, gzipped.readIntLe());

    // Set an incorrect length.
    gzipped = gzip(buffer(original), new GzipOptions().length(0x123456));
    try {
      gunzip(gzipped);
      fail();
    } catch (IOException e) {
      assertEquals("ISIZE: actual 0x000020 != expected 0x123456", e.getMessage());
    }
  }

  /** Use GZIPOutputStream to gzip a buffer. */
  private OkBuffer gzip(OkBuffer buffer, final GzipOptions options) throws IOException {
    OkBuffer result = new OkBuffer();
    Sink sink = OkBuffers.sink(new GZIPOutputStream(OkBuffers.outputStream(result), options));
    sink.write(buffer, buffer.byteCount(), Deadline.NONE);
    sink.close(Deadline.NONE);
    return result;
  }

  private OkBuffer gunzip(OkBuffer gzipped) throws IOException {
    OkBuffer result = new OkBuffer();
    GzipSource source = new GzipSource(gzipped);
    while (source.read(result, Integer.MAX_VALUE, Deadline.NONE) != -1) {
    }
    return result;
  }

  private OkBuffer buffer(String s) {
    OkBuffer result = new OkBuffer();
    result.writeUtf8(s);
    return result;
  }

  private static class GzipOptions {
    static final GzipOptions NONE = new GzipOptions();

    private byte flags;
    private byte[] extra;
    private String name;
    private String comment;
    private Short headerCrc32;
    private Integer crc32;
    private Integer length;

    /** Store the CRC-32 of the header. */
    GzipOptions headerCrc32(Short headerCrc32) {
      this.headerCrc32 = headerCrc32;
      flags |= (1 << 1);
      return this;
    }

    /** Store extra fields in the header. */
    GzipOptions extra(byte[] extra) {
      this.extra = extra;
      flags |= (1 << 2);
      return this;
    }

    /** Store the file name in the header. */
    GzipOptions name(String name) {
      this.name = name;
      flags |= (1 << 3);
      return this;
    }

    /** Store the file comment in the header. */
    GzipOptions comment(String comment) {
      this.comment = comment;
      flags |= (1 << 4);
      return this;
    }

    /** Overwrite the CRC-32 in the trailer. */
    GzipOptions crc32(Integer crc32) {
      this.crc32 = crc32;
      return this;
    }

    /** Overwrite the length in the trailer. */
    GzipOptions length(Integer length) {
      this.length = length;
      return this;
    }
  }

  /**
   * Allows us to customize a gzip impl someone else wrote so that we can test our implementation.
   */
  private static class GZIPOutputStream extends java.util.zip.GZIPOutputStream {
    private final GzipOptions options;

    private GZIPOutputStream(OutputStream out, final GzipOptions options)
        throws IOException {
      super(new FilterOutputStream(out) { // intercept the header
        private int pos;

        @Override public void write(int b) throws IOException {
          if (pos++ == 3) b = options.flags;
          super.write(b);
        }
      });
      // superclass constructor ends following the header.
      if (options.extra != null) {
        // write the little-endian extra data length.
        out.write((byte) options.extra.length & 0xff);
        out.write((byte) (options.extra.length >> 8) & 0xff);
        out.write(options.extra);
      }
      if (options.name != null) {
        out.write(options.name.getBytes(Util.US_ASCII));
        out.write(0); // name is zero-terminated
      }
      if (options.comment != null) {
        out.write(options.comment.getBytes(Util.US_ASCII));
        out.write(0); // comment is zero-terminated
      }
      if (options.headerCrc32 != null) {
        // write the little-endian header crc.
        out.write((byte) (options.headerCrc32 & 0xff));
        out.write((byte) ((options.headerCrc32 >> 8) & 0xff));
      }
      this.out = new LeakyBufferedOutputStream(out);
      this.options = options;
    }

    @Override public void finish() throws IOException {
      super.finish();
      byte[] lastBytes = ((LeakyBufferedOutputStream) out).buf();
      int offset = ((LeakyBufferedOutputStream) out).count() - 8;
      if (options.crc32 != null) {
        int crc32 = options.crc32;
        // write the CRC-32 little endian, at the beginning of the trailer.
        lastBytes[offset + 3] = (byte) ((crc32 >> 24) & 0xff);
        lastBytes[offset + 2] = (byte) ((crc32 >> 16) & 0xff);
        lastBytes[offset + 1] = (byte) ((crc32 >> 8) & 0xff);
        lastBytes[offset + 0] = (byte) (crc32 & 0xff);
      }
      if (options.length != null) {
        int length = options.length;
        // write the length little endian, at the end of the trailer.
        lastBytes[offset + 7] = (byte) ((length >> 24) & 0xff);
        lastBytes[offset + 6] = (byte) ((length >> 16) & 0xff);
        lastBytes[offset + 5] = (byte) ((length >> 8) & 0xff);
        lastBytes[offset + 4] = (byte) (length & 0xff);
      }
      flush();
    }

    /** Leaks a buffer, so that we can go back and rewrite the trailer. */
    private class LeakyBufferedOutputStream extends BufferedOutputStream {
      private LeakyBufferedOutputStream(OutputStream out) {
        super(out, GZIPOutputStream.this.buf.length + 8); // deflater buffer + trailer size
      }

      private byte[] buf() {
        return buf;
      }

      public int count() {
        return count;
      }
    }
  }
}
