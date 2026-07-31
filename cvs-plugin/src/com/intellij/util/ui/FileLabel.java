// Compatibility shim for removed platform class com.intellij.util.ui.FileLabel (pre-2026).
// Minimal API surface used by CVS add-confirmation UI.
package com.intellij.util.ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class FileLabel extends JLabel {
  private boolean myShowIcon = true;
  private File myFile;

  public void setShowIcon(boolean showIcon) {
    myShowIcon = showIcon;
    if (!showIcon) {
      super.setIcon(null);
    }
  }

  public void setFile(File file) {
    myFile = file;
    setText(file == null ? "" : getFilePath(file));
  }

  public File getFile() {
    return myFile;
  }

  public static String getFilePath(File file) {
    return file == null ? "" : file.getPath();
  }

  public int getIconWidth() {
    Icon icon = getIcon();
    return icon == null ? 0 : icon.getIconWidth();
  }

  @Override
  public void setIcon(Icon icon) {
    if (myShowIcon) {
      super.setIcon(icon);
    }
    else {
      super.setIcon(null);
    }
  }

  public void pack() {
    Dimension preferred = getPreferredSize();
    setPreferredSize(preferred);
  }
}
