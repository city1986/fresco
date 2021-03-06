/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imageformat;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import com.facebook.common.internal.ByteStreams;
import com.facebook.common.internal.Closeables;
import com.facebook.common.internal.Ints;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.Throwables;
import com.facebook.common.webp.WebpSupportStatus;

/**
 * Detects the format of an encoded image.
 */
public class ImageFormatChecker {

  private ImageFormatChecker() {}

  /**
   * Tries to match imageHeaderByte and headerSize against every known image format.
   * If any match succeeds, corresponding ImageFormat is returned.
   * @param imageHeaderBytes
   * @param headerSize
   * @return ImageFormat for given imageHeaderBytes or UNKNOWN if no such type could be recognized
   */
  private static ImageFormat doGetImageFormat(
      final byte[] imageHeaderBytes,
      final int headerSize) {
    Preconditions.checkNotNull(imageHeaderBytes);

    if (WebpSupportStatus.isWebpHeader(imageHeaderBytes, 0, headerSize)) {
      return getWebpFormat(imageHeaderBytes, headerSize);
    }

    if (isJpegHeader(imageHeaderBytes, headerSize)) {
      return DefaultImageFormats.JPEG;
    }

    if (isPngHeader(imageHeaderBytes, headerSize)) {
      return DefaultImageFormats.PNG;
    }

    if (isGifHeader(imageHeaderBytes, headerSize)) {
      return DefaultImageFormats.GIF;
    }

    if (isBmpHeader(imageHeaderBytes, headerSize)) {
      return DefaultImageFormats.BMP;
    }

    return ImageFormat.UNKNOWN;
  }

  /**
   * Reads up to MAX_HEADER_LENGTH bytes from is InputStream. If mark is supported by is, it is
   * used to restore content of the stream after appropriate amount of data is read.
   * Read bytes are stored in imageHeaderBytes, which should be capable of storing
   * MAX_HEADER_LENGTH bytes.
   * @param is
   * @param imageHeaderBytes
   * @return number of bytes read from is
   * @throws IOException
   */
  private static int readHeaderFromStream(
      final InputStream is,
      final byte[] imageHeaderBytes)
      throws IOException {
    Preconditions.checkNotNull(is);
    Preconditions.checkNotNull(imageHeaderBytes);
    Preconditions.checkArgument(imageHeaderBytes.length >= MAX_HEADER_LENGTH);

    // If mark is supported by the stream, use it to let the owner of the stream re-read the same
    // data. Otherwise, just consume some data.
    if (is.markSupported()) {
      try {
        is.mark(MAX_HEADER_LENGTH);
        return ByteStreams.read(is, imageHeaderBytes, 0, MAX_HEADER_LENGTH);
      } finally {
        is.reset();
      }
    } else {
      return ByteStreams.read(is, imageHeaderBytes, 0, MAX_HEADER_LENGTH);
    }
  }

  /**
   * Tries to read up to MAX_HEADER_LENGTH bytes from InputStream is and use read bytes to
   * determine type of the image contained in is. If provided input stream does not support mark,
   * then this method consumes data from is and it is not safe to read further bytes from is after
   * this method returns. Otherwise, if mark is supported, it will be used to preserve original
   * content of is.
   * @param is
   * @return ImageFormat matching content of is InputStream or UNKNOWN if no type is suitable
   * @throws IOException if exception happens during read
   */
  public static ImageFormat getImageFormat(final InputStream is) throws IOException {
    Preconditions.checkNotNull(is);
    final byte[] imageHeaderBytes = new byte[MAX_HEADER_LENGTH];
    final int headerSize = readHeaderFromStream(is, imageHeaderBytes);
    return doGetImageFormat(imageHeaderBytes, headerSize);
  }

  /*
   * A variant of getImageFormat that wraps IOException with RuntimeException.
   * This relieves clients of implementing dummy rethrow try-catch block.
   */
  public static ImageFormat getImageFormat_WrapIOException(final InputStream is) {
    try {
      return getImageFormat(is);
    } catch (IOException ioe) {
      throw Throwables.propagate(ioe);
    }
  }

  /**
   * Reads image header from a file indicated by provided filename and determines
   * its format. This method does not throw IOException if one occurs. In this case,
   * {@link ImageFormat#UNKNOWN} will be returned.
   * @param filename
   * @return ImageFormat for image stored in filename
   */
  public static ImageFormat getImageFormat(String filename) {
    FileInputStream fileInputStream = null;
    try {
      fileInputStream = new FileInputStream(filename);
      return getImageFormat(fileInputStream);
    } catch (IOException ioe) {
      return ImageFormat.UNKNOWN;
    } finally {
      Closeables.closeQuietly(fileInputStream);
    }
  }

  /**
   * Checks if byteArray interpreted as sequence of bytes has a subsequence equal to pattern
   * starting at position equal to offset.
   * @param byteArray
   * @param offset
   * @param pattern
   * @return true if match succeeds, false otherwise
   */
  private static boolean matchBytePattern(
      final byte[] byteArray,
      final int offset,
      final byte[] pattern) {
    Preconditions.checkNotNull(byteArray);
    Preconditions.checkNotNull(pattern);
    Preconditions.checkArgument(offset >= 0);
    if (pattern.length + offset > byteArray.length) {
      return false;
    }

    for (int i = 0; i < pattern.length; ++i) {
      if (byteArray[i + offset] != pattern[i]) {
        return false;
      }
    }

    return true;
  }

  /**
   * Helper method that transforms provided string into it's byte representation
   * using ASCII encoding
   * @param value
   * @return byte array representing ascii encoded value
   */
  private static byte[] asciiBytes(String value) {
    Preconditions.checkNotNull(value);
    try {
      return value.getBytes("ASCII");
    } catch (UnsupportedEncodingException uee) {
      // won't happen
      throw new RuntimeException("ASCII not found!", uee);
    }
  }


  /**
   * Each WebP header should cosist of at least 20 bytes and start
   * with "RIFF" bytes followed by some 4 bytes and "WEBP" bytes.
   * More detailed description if WebP can be found here:
   * <a href="https://developers.google.com/speed/webp/docs/riff_container">
   *   https://developers.google.com/speed/webp/docs/riff_container</a>
   */
  private static final int SIMPLE_WEBP_HEADER_LENGTH = 20;

  /**
   * Each VP8X WebP image has "features" byte following its ChunkHeader('VP8X')
   */
  private static final int EXTENDED_WEBP_HEADER_LENGTH = 21;

  /**
   * Determines type of WebP image. imageHeaderBytes has to be header of a WebP image
   */
  private static ImageFormat getWebpFormat(final byte[] imageHeaderBytes, final int headerSize) {
    Preconditions.checkArgument(WebpSupportStatus.isWebpHeader(imageHeaderBytes, 0, headerSize));
    if (WebpSupportStatus.isSimpleWebpHeader(imageHeaderBytes, 0)) {
      return DefaultImageFormats.WEBP_SIMPLE;
    }

    if (WebpSupportStatus.isLosslessWebpHeader(imageHeaderBytes, 0)) {
      return DefaultImageFormats.WEBP_LOSSLESS;
    }

    if (WebpSupportStatus.isExtendedWebpHeader(imageHeaderBytes, 0, headerSize)) {
      if (WebpSupportStatus.isAnimatedWebpHeader(imageHeaderBytes, 0)) {
        return DefaultImageFormats.WEBP_ANIMATED;
      }
      if (WebpSupportStatus.isExtendedWebpHeaderWithAlpha(imageHeaderBytes, 0)) {
        return DefaultImageFormats.WEBP_EXTENDED_WITH_ALPHA;
      }
      return DefaultImageFormats.WEBP_EXTENDED;
    }

    return ImageFormat.UNKNOWN;
  }

  /**
   * Every JPEG image should start with SOI mark (0xFF, 0xD8) followed by beginning
   * of another segment (0xFF)
   */
  private static final byte[] JPEG_HEADER = new byte[] {(byte)0xFF, (byte)0xD8, (byte)0xFF};

  /**
   * Checks if imageHeaderBytes starts with SOI (start of image) marker, followed by 0xFF.
   * If headerSize is lower than 3 false is returned.
   * Description of jpeg format can be found here:
   * <a href="http://www.w3.org/Graphics/JPEG/itu-t81.pdf">
   *   http://www.w3.org/Graphics/JPEG/itu-t81.pdf</a>
   * Annex B deals with compressed data format
   * @param imageHeaderBytes
   * @param headerSize
   * @return true if imageHeaderBytes starts with SOI_BYTES and headerSize >= 3
   */
  private static boolean isJpegHeader(final byte[] imageHeaderBytes, final int headerSize) {
    return headerSize >= JPEG_HEADER.length && matchBytePattern(imageHeaderBytes, 0, JPEG_HEADER);
  }


  /**
   * Every PNG image starts with 8 byte signature consisting of
   * following bytes
   */
  private static final byte[] PNG_HEADER = new byte[] {
      (byte) 0x89,
      'P', 'N', 'G',
      (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};

  /**
   * Checks if array consisting of first headerSize bytes of imageHeaderBytes
   * starts with png signature. More information on PNG can be found there:
   * <a href="http://en.wikipedia.org/wiki/Portable_Network_Graphics">
   *   http://en.wikipedia.org/wiki/Portable_Network_Graphics</a>
   * @param imageHeaderBytes
   * @param headerSize
   * @return true if imageHeaderBytes starts with PNG_HEADER
   */
  private static boolean isPngHeader(final byte[] imageHeaderBytes, final int headerSize) {
    return headerSize >= PNG_HEADER.length && matchBytePattern(imageHeaderBytes, 0, PNG_HEADER);
  }


  /**
   * Every gif image starts with "GIF" bytes followed by
   * bytes indicating version of gif standard
   */
  private static final byte[] GIF_HEADER_87A = asciiBytes("GIF87a");
  private static final byte[] GIF_HEADER_89A = asciiBytes("GIF89a");
  private static final int GIF_HEADER_LENGTH = 6;

  /**
   * Checks if first headerSize bytes of imageHeaderBytes constitute a valid header for a gif image.
   * Details on GIF header can be found <a href="http://www.w3.org/Graphics/GIF/spec-gif89a.txt">
   *  on page 7</a>
   * @param imageHeaderBytes
   * @param headerSize
   * @return true if imageHeaderBytes is a valid header for a gif image
   */
  private static boolean isGifHeader(final byte[] imageHeaderBytes, final int headerSize) {
    if (headerSize < GIF_HEADER_LENGTH) {
      return false;
    }
    return matchBytePattern(imageHeaderBytes, 0, GIF_HEADER_87A) ||
        matchBytePattern(imageHeaderBytes, 0, GIF_HEADER_89A);
  }

  /**
   * Every bmp image starts with "BM" bytes
   */
  private static final byte[] BMP_HEADER = asciiBytes("BM");

  /**
   * Checks if first headerSize bytes of imageHeaderBytes constitute a valid header for a bmp image.
   * Details on BMP header can be found <a href="http://www.onicos.com/staff/iz/formats/bmp.html">
   * </a>
   * @param imageHeaderBytes
   * @param headerSize
   * @return true if imageHeaderBytes is a valid header for a bmp image
   */
  private static boolean isBmpHeader(final byte[] imageHeaderBytes, final int headerSize) {
    if (headerSize < BMP_HEADER.length) {
      return false;
    }
    return matchBytePattern(imageHeaderBytes, 0, BMP_HEADER);
  }


  /**
   * Maximum header size for any image type.
   *
   * <p>This determines how much data {@link #getImageFormat(InputStream)
   * reads from a stream. After changing any of the type detection algorithms, or adding a new one,
   * this value should be edited.
   */
  private static final int MAX_HEADER_LENGTH = Ints.max(
      EXTENDED_WEBP_HEADER_LENGTH,
      SIMPLE_WEBP_HEADER_LENGTH,
      JPEG_HEADER.length,
      PNG_HEADER.length,
      GIF_HEADER_LENGTH,
      BMP_HEADER.length);
}
