// Compatibility shim for removed platform class com.intellij.util.ui.EditorAdapter.
package com.intellij.util.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Minimal stand-in: appends text to the editor document (attributes ignored).
 */
public class EditorAdapter {
  private final Editor myEditor;

  public EditorAdapter(@NotNull Editor editor, Project project, boolean scrollToEnd) {
    myEditor = editor;
  }

  public void appendString(String message, TextAttributes attributes) {
    if (message == null) return;
    ApplicationManager.getApplication().invokeLater(() -> {
      ApplicationManager.getApplication().runWriteAction(() -> {
        Document document = myEditor.getDocument();
        document.insertString(document.getTextLength(), message.endsWith("\n") ? message : message + "\n");
        myEditor.getCaretModel().moveToOffset(document.getTextLength());
        myEditor.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE);
      });
    });
  }
}
