package com.welovecoding.netbeans.plugin.editorconfig.processor;

import com.welovecoding.netbeans.plugin.editorconfig.mapper.EditorConfigPropertyMapper;
import com.welovecoding.netbeans.plugin.editorconfig.model.EditorConfigConstant;
import com.welovecoding.netbeans.plugin.editorconfig.processor.operation.IndentSizeOperation;
import com.welovecoding.netbeans.plugin.editorconfig.processor.operation.IndentStyleOperation;
import com.welovecoding.netbeans.plugin.editorconfig.processor.operation.XFinalNewLineOperation;
import com.welovecoding.netbeans.plugin.editorconfig.processor.operation.XLineEndingOperation;
import com.welovecoding.netbeans.plugin.editorconfig.processor.operation.XTabWidthOperation;
import com.welovecoding.netbeans.plugin.editorconfig.processor.operation.XTrimTrailingWhitespacesOperation;
import com.welovecoding.netbeans.plugin.editorconfig.util.NetBeansFileUtil;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import org.editorconfig.core.EditorConfig;
import org.editorconfig.core.EditorConfigException;
import org.netbeans.editor.BaseDocument;
import org.netbeans.modules.editor.indent.spi.CodeStylePreferences;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.text.NbDocument;
import org.openide.util.Exceptions;

/**
 *
 * @author Michael Koppen
 */
public class EditorConfigProcessor {

  private static final Logger LOG = Logger.getLogger(EditorConfigProcessor.class.getName());
  private final EditorConfig ec;

  public EditorConfigProcessor() {
    ec = new EditorConfig(".editorconfig", EditorConfig.VERSION);
  }

  private HashMap<String, String> parseRulesForFile(DataObject dataObject) {
    String filePath = dataObject.getPrimaryFile().getPath();

    LOG.log(Level.INFO, "Apply rules for: {0}", filePath);

    List<EditorConfig.OutPair> rules = new ArrayList<>();

    try {
      rules = ec.getProperties(filePath);
    } catch (EditorConfigException ex) {
      LOG.log(Level.SEVERE, ex.getMessage());
    }

    HashMap<String, String> keyedRules = new HashMap<>();
    for (EditorConfig.OutPair rule : rules) {
      keyedRules.put(rule.getKey().toLowerCase(), rule.getVal().toLowerCase());
    }
    return keyedRules;
  }

  /**
   * Applies EditorConfig rules for the given file.
   *
   * @param dataObject
   */
  public void applyRulesToFile(DataObject dataObject) throws Exception {

    HashMap<String, String> keyedRules = parseRulesForFile(dataObject);

    FileObject fileObject = dataObject.getPrimaryFile();

    // Save file before appling any changes when opened in editor
    EditorCookie cookie = getEditorCookie(dataObject);
    boolean isOpenedInEditor = cookie != null && cookie.getDocument() != null;

//    if (isOpenedInEditor) {
//      LOG.log(Level.INFO, "File is opened in Editor! Saving all changes.");
//      StyledDocument document = cookie.getDocument();
//      NbDocument.runAtomicAsUser(document, () -> {
//        try {
//          cookie.saveDocument();
//        } catch (IOException ex) {
//          Exceptions.printStackTrace(ex);
//        }
//      });
//    }
    StringBuilder content = new StringBuilder(fileObject.asText());
    boolean fileChange = false;
    boolean charsetChange = false;
    boolean styleChange = false;

    for (Map.Entry<String, String> rule : keyedRules.entrySet()) {
      final String key = rule.getKey();
      final String value = rule.getValue();

      LOG.log(Level.INFO, "Found rule \"{0}\" with value \"{1}\".", new Object[]{key, value});

      switch (key) {
        case EditorConfigConstant.CHARSET:
          Charset currentCharset = NetBeansFileUtil.guessCharset(fileObject);
          Charset requestedCharset = EditorConfigPropertyMapper.mapCharset(keyedRules.get(EditorConfigConstant.CHARSET));
          if (!currentCharset.equals(requestedCharset)) {
            charsetChange = true;
          }
          break;
        case EditorConfigConstant.END_OF_LINE:
          boolean tempFileChange = XLineEndingOperation.doChangeLineEndings(
                  content,
                  EditorConfigPropertyMapper.normalizeLineEnding(keyedRules.get(EditorConfigConstant.END_OF_LINE)));

          // changing lineendings in document aswell
          StyledDocument document = NbDocument.getDocument(dataObject);
          if (tempFileChange && document != null) {
            if (!document.getProperty(BaseDocument.READ_LINE_SEPARATOR_PROP).equals(EditorConfigPropertyMapper.normalizeLineEnding(keyedRules.get(EditorConfigConstant.END_OF_LINE)))) {
              document.putProperty(BaseDocument.READ_LINE_SEPARATOR_PROP, EditorConfigPropertyMapper.normalizeLineEnding(keyedRules.get(EditorConfigConstant.END_OF_LINE)));
              LOG.log(Level.INFO, "Action: Changed line endings in Document");
              fileChange = tempFileChange || fileChange;
            } else {
              LOG.log(Level.INFO, "Action not needed: Line endings are already correct");
            }
          }
          break;
        case EditorConfigConstant.INDENT_SIZE:
          //TODO this should happen in the file!!
          styleChange = IndentSizeOperation.doIndentSize(dataObject, value) || styleChange;
          break;
        case EditorConfigConstant.INDENT_STYLE:
          //TODO this happens in the file!!
          styleChange = IndentStyleOperation.doIndentStyle(dataObject, key) || styleChange;
          break;
        case EditorConfigConstant.INSERT_FINAL_NEWLINE:
          fileChange = XFinalNewLineOperation.doFinalNewLine(
                  content,
                  value,
                  EditorConfigPropertyMapper.normalizeLineEnding(keyedRules.get(EditorConfigConstant.END_OF_LINE))) || fileChange;
          break;
        case EditorConfigConstant.TAB_WIDTH:
          styleChange = XTabWidthOperation.doTabWidth(dataObject, value) || styleChange;
          break;
        case EditorConfigConstant.TRIM_TRAILING_WHITESPACE:
          fileChange = XTrimTrailingWhitespacesOperation.doTrimTrailingWhitespaces(
                  content,
                  value,
                  EditorConfigPropertyMapper.normalizeLineEnding(keyedRules.get(EditorConfigConstant.END_OF_LINE))) || fileChange;
          break;
        default:
          LOG.log(Level.WARNING, "Unknown property: {0}", key);
          break;
      }
    }

    flushFile(
            fileObject,
            content,
            fileChange,
            charsetChange,
            EditorConfigPropertyMapper.mapCharset(keyedRules.get(EditorConfigConstant.CHARSET)),
            isOpenedInEditor,
            cookie);

    flushStyles(fileObject, styleChange);

  }

  private void flushFile(FileObject fileObject, StringBuilder content, boolean changed, boolean charsetChange, Charset charset, boolean flushInEditor, EditorCookie cookie) throws BadLocationException {
    if (changed || charsetChange) {
      if (!flushInEditor) {
        new WriteFileTask(fileObject, charset) {
          @Override
          public void apply(OutputStreamWriter writer) {
            try {
              writer.write(content.toString());
            } catch (IOException ex) {
              Exceptions.printStackTrace(ex);
            }
          }
        }.run();
      } else {
        LOG.log(Level.INFO, "Update changes in Editor window");

        NbDocument.runAtomic(cookie.getDocument(), () -> {
          try {
            StyledDocument newDocument = cookie.openDocument();
            newDocument.remove(0, newDocument.getLength());
            newDocument.insertString(0, new String(content.toString().getBytes(charset)), null);
            cookie.saveDocument();
          } catch (BadLocationException | IOException ex) {
            Exceptions.printStackTrace(ex);
          }
        });
      }
    }
  }

  private void flushStyles(FileObject fileObject, boolean styleChanged) {
    if (styleChanged) {
      try {
        Preferences codeStyle = CodeStylePreferences.get(fileObject, fileObject.getMIMEType()).getPreferences();
        codeStyle.flush();
      } catch (BackingStoreException ex) {
        LOG.log(Level.SEVERE, "Error applying code style: {0}", ex.getMessage());
      }
    }
  }

  private EditorCookie getEditorCookie(FileObject fileObject) {
    try {
      return (EditorCookie) DataObject.find(fileObject).getLookup().lookup(EditorCookie.class);
    } catch (DataObjectNotFoundException ex) {
      Exceptions.printStackTrace(ex);
      return null;
    }
  }

  private EditorCookie getEditorCookie(DataObject dataObject) {
    return dataObject.getLookup().lookup(EditorCookie.class);
  }

  private void setFileAttribute(FileObject fo, String key, String value) {
    try {
      fo.setAttribute(key, value);
    } catch (IOException ex) {
      LOG.log(Level.SEVERE, "Error setting file attribute \"{0}\" with value \"{1}\" for {2}. {3}",
              new Object[]{
                key,
                value,
                fo.getPath(),
                ex.getMessage()
              });
    }
  }
}
