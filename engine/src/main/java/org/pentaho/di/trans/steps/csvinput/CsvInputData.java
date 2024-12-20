/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.di.trans.steps.csvinput;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;

import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.step.BaseStepData;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.steps.textfileinput.EncodingType;

/**
 * @author Matt
 * @since 24-jan-2005
 */
public class CsvInputData extends BaseStepData implements StepDataInterface {
  public FileChannel fc;
  public ByteBuffer bb;
  public RowMetaInterface convertRowMeta;
  public RowMetaInterface outputRowMeta;

  private byte[] byteBuffer;
  private int startBuffer;
  private int endBuffer;
  private int bufferSize;

  public byte[] delimiter;
  public byte[] enclosure;

  public int preferredBufferSize;
  public String[] filenames;
  public int filenr;
  public int startFilenr;
  public byte[] binaryFilename;
  public FileInputStream fis;

  public boolean isAddingRowNumber;
  public long rowNumber;
  public boolean stopReading;
  public int stepNumber;
  public int totalNumberOfSteps;
  public List<Long> fileSizes;
  public long totalFileSize;
  public long blockToRead;
  public long startPosition;
  public long endPosition;
  public long bytesToSkipInFirstFile;

  public long totalBytesRead;

  public boolean parallel;
  public int filenameFieldIndex;
  public int rownumFieldIndex;
  public EncodingType encodingType;
  public PatternMatcherInterface delimiterMatcher;
  public PatternMatcherInterface enclosureMatcher;
  public CrLfMatcherInterface crLfMatcher;

  public FieldsMapping fieldsMapping;

  /**
   * Data class for CsvInput step
   *
   * @see CsvInput
   */
  public CsvInputData() {
    super();
    byteBuffer = new byte[] {};
    startBuffer = 0;
    endBuffer = 0;
    totalBytesRead = 0;
  }

  // Resize
  private void resizeByteBufferArray() {
    // What's the new size?
    // It's (endBuffer-startBuffer)+size !!
    // That way we can at least read one full block of data using NIO
    //
    bufferSize = endBuffer - startBuffer;
    int newSize = bufferSize + preferredBufferSize;
    byte[] newByteBuffer = new byte[newSize + 100];

    // copy over the old data...
    System.arraycopy( byteBuffer, startBuffer, newByteBuffer, 0, bufferSize );

    // replace the old byte buffer...
    byteBuffer = newByteBuffer;

    // Adjust start and end point of data in the byte buffer
    //
    startBuffer = 0;
    endBuffer = bufferSize;
  }

  private int readBufferFromFile() throws IOException {
    // See if the line is not longer than the buffer.
    // In that case we need to increase the size of the byte buffer.
    // Since this method doesn't get called every other character, I'm sure we can spend a bit of time here without
    // major performance loss.
    //
    if ( endBuffer >= bb.capacity() ) {
      resizeByteBuffer( (int) ( bb.capacity() * 1.5 ) );
    }

    bb.position( endBuffer );
    int n = fc.read( bb );
    if ( n >= 0 ) {

      // adjust the highest used position...
      //
      bufferSize = endBuffer + n;

      // Make sure we have room in the target byte buffer array
      //
      if ( byteBuffer.length < bufferSize ) {
        byte[] newByteBuffer = new byte[bufferSize];
        System.arraycopy( byteBuffer, 0, newByteBuffer, 0, byteBuffer.length );
        byteBuffer = newByteBuffer;
      }

      // Store the data in our byte array
      //
      bb.position( endBuffer );
      bb.get( byteBuffer, endBuffer, n );
    }

    return n;
  }

  private void resizeByteBuffer( int newSize ) {
    ByteBuffer newBuffer = ByteBuffer.allocateDirect( newSize ); // Increase by 50%
    newBuffer.position( 0 );
    newBuffer.put( bb );
    bb = newBuffer;
  }

  /**
   * Check to see if the buffer size is large enough given the data.endBuffer pointer.<br>
   * Resize the buffer if there is not enough room.
   *
   * @return false if everything is OK, true if there is a problem and we should stop.
   * @throws IOException
   *           in case there is a I/O problem (read error)
   */
  boolean resizeBufferIfNeeded() throws IOException {
    if ( endOfBuffer() ) {
      // Oops, we need to read more data...
      // Better resize this before we read other things in it...
      //
      resizeByteBufferArray();

      // Also read another chunk of data, now that we have the space for it...
      //
      int n = readBufferFromFile();

      // If we didn't manage to read something, we return true to indicate we're done
      //
      return n < 0;
    }

    return false;
  }

  /**
   * Moves the endBuffer pointer by one.<br>
   * If there is not enough room in the buffer to go there, resize the byte buffer and read more data.<br>
   * if there is no more data to read and if the endBuffer pointer has reached the end of the byte buffer, we return
   * true.<br>
   *
   * @return true if we reached the end of the byte buffer.
   * @throws IOException
   *           In case we get an error reading from the input file.
   */
  boolean moveEndBufferPointer() throws IOException {
    return moveEndBufferPointer( true );
  }

  void moveEndBufferPointerXTimes( int xTimes ) throws IOException {
    for ( int i = 0; i < xTimes; i++ ) {
      moveEndBufferPointer( true );
    }
  }

  /**
   * This method should be used very carefully. Moving pointer without increasing number of written bytes
   * can lead to data corruption.
   */
  boolean moveEndBufferPointer( boolean increaseTotalBytes ) throws IOException {
    endBuffer++;
    if ( increaseTotalBytes ) {
      totalBytesRead++;
    }
    return resizeBufferIfNeeded();
  }

  /**
   * <pre>
   *       [abcd "" defg] --> [abcd " defg]
   *       [""""] --> [""]
   *       [""] --> ["]
   * </pre>
   *
   * @return the byte array with escaped enclosures escaped.
   */
  byte[] removeEscapedEnclosures( byte[] field, int nrEnclosuresFound ) {
    byte[] result = new byte[field.length - nrEnclosuresFound];
    int resultIndex = 0;
    for ( int i = 0; i < field.length; i++ ) {
      result[resultIndex++] = field[i];
      if ( field[i] == enclosure[0] && i + 1 < field.length && field[i + 1] == enclosure[0] ) {
        // Skip the escaped enclosure after adding the first one
        i++;
      }
    }
    return result;
  }

  byte[] getField( boolean delimiterFound, boolean enclosureFound, boolean newLineFound, boolean endOfBuffer ) {
    int fieldStart = startBuffer;
    int fieldEnd = endBuffer;

    if ( newLineFound && !endOfBuffer ) {
      fieldEnd -= encodingType.getLength();
    }

    if ( enclosureFound ) {
      fieldStart += enclosure.length;
      fieldEnd -= enclosure.length;
    }

    int length = fieldEnd - fieldStart;

    if ( length <= 0 ) {
      length = 0;
    }

    byte[] field = new byte[length];
    System.arraycopy( byteBuffer, fieldStart, field, 0, length );

    return field;
  }

  void closeFile() throws KettleException {
    try {
      if ( fc != null ) {
        fc.close();
      }
      if ( fis != null ) {
        fis.close();
      }
    } catch ( IOException e ) {
      throw new KettleException( "Unable to close file channel for file '" + filenames[filenr - 1], e );
    }
  }

  int getStartBuffer() {
    return startBuffer;
  }

  void setStartBuffer( int startBuffer ) {
    this.startBuffer = startBuffer;
  }

  int getEndBuffer() {
    return endBuffer;
  }

  boolean isCarriageReturn() {
    return encodingType.isReturn( byteBuffer[endBuffer] );
  }

  boolean newLineFound() {
    return crLfMatcher.isReturn( byteBuffer, endBuffer ) || crLfMatcher.isLineFeed( byteBuffer, endBuffer );
  }

  boolean delimiterFound() throws IOException {
    checkMinimumBytesAvailable( delimiter.length );
    return delimiterMatcher.matchesPattern( byteBuffer, endBuffer, delimiter );
  }

  boolean enclosureFound() {
    return enclosureMatcher.matchesPattern( byteBuffer, endBuffer, enclosure );
  }

  boolean endOfBuffer() {
    return endBuffer >= bufferSize;
  }

  /**
   * Ensure there are at least a minimum number of bytes in the buffer.  If not then pull in another block of data.
   * @param bytesNeeded bytes to ensure are present in the buffer
   */
  private void checkMinimumBytesAvailable( int bytesNeeded ) throws IOException {
    if ( bufferSize - endBuffer < bytesNeeded ) {

      int newSize = bufferSize - startBuffer + Math.max( preferredBufferSize, bytesNeeded );
      byte[] newByteBuffer = new byte[ newSize + 100 ];

      // copy over the old data...
      System.arraycopy( byteBuffer, startBuffer, newByteBuffer, 0, bufferSize - startBuffer );

      // replace the old byte buffer...
      byteBuffer = newByteBuffer;

      // Adjust start and end point of data in the byte buffer
      //
      int newEndBuffer = endBuffer - startBuffer;
      bufferSize =
        endBuffer = bufferSize - startBuffer;  //Set endBuffer to bufferSize temporarily so readBufferFromFile works
      startBuffer = 0;

      readBufferFromFile();

      endBuffer = newEndBuffer;

    }
  }

}
