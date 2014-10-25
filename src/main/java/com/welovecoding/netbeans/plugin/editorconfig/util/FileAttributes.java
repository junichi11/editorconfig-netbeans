package com.welovecoding.netbeans.plugin.editorconfig.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;

public class FileAttributes {

  private static final Logger LOG = Logger.getLogger(FileAttributes.class.getName());

  public static boolean hasFinalNewLine(String filePath) {
    File file = FileUtils.getFile(filePath);
    return hasFinalNewLine(file);
  }

  public static boolean hasFinalNewLine(File file) {
    boolean isNewLine = false;
    String lastLine = readLastBytes(file, 2);

    if (lastLine.endsWith("\r") || lastLine.endsWith("\n")) {
      isNewLine = true;
    }

    return isNewLine;
  }

  /**
   * Reads the amount (n) bytes from the end of a file.
   *
   * @param file File from which to read from.
   * @param length Amount of bytes to read.
   * @return
   */
  public static String readLastBytes(File file, int length) {
    byte[] byteArray = new byte[length];

    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      raf.seek(file.length() - length);
      raf.read(byteArray, 0, length);
    } catch (IOException ex) {
      LOG.log(Level.SEVERE, ex.getMessage());
    }

    return new String(byteArray);
  }
}
