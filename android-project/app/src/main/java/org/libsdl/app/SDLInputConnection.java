package org.libsdl.app;

import android.content.*;
import android.os.Build;
import android.text.Editable;
import android.view.*;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;

class SDLInputConnection extends BaseInputConnection
{
    // The text the keyboard reads and edits, and what of it the app has been sent: one for every
    // connection, so that a keyboard still calling a connection made before the current one edits
    // the same text.
    protected static EditText mEditText;
    protected static String mCommittedText = "";

    // The app's text before its cursor, which the next connection starts from when sReset is set, so
    // that the keyboard composes and corrects in its context (SDLActivity.restartTextInput)
    static volatile String sBefore = "";
    static volatile boolean sReset;

    SDLInputConnection(View targetView, boolean fullEditor) {
        super(targetView, fullEditor);
        if (mEditText == null) {
            mEditText = new EditText(SDL.getContext());
        }
        if (sReset) {
            String before = sBefore;
            mEditText.setText(before);
            mEditText.setSelection(before.length());
            mCommittedText = before;
            sReset = false;
        }
    }

    @Override
    public Editable getEditable() {
        return mEditText.getEditableText();
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        long at = System.nanoTime();
        /*
         * This used to handle the keycodes from soft keyboard (and IME-translated input from hardkeyboard)
         * However, as of Ice Cream Sandwich and later, almost all soft keyboard doesn't generate key presses
         * and so we need to generate them ourselves in commitText.  To avoid duplicates on the handful of keys
         * that still do, we empty this out.
         */

        /*
         * Return DOES still generate a key event, however.  So rather than using it as the 'click a button' key
         * as we do with physical keyboards, let's just use it to hide the keyboard.
         */

        if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
            if (SDLActivity.onNativeSoftReturnKey()) {
                return true;
            }
        }

        /*
         * A keyboard may delete by sending Delete or Forward Delete as keys rather than through
         * deleteSurroundingText. The key reaches the app, which deletes; the editable the keyboard reads
         * must lose the same characters, or its later corrections, diffed in updateText against
         * mCommittedText, delete too much.
         */
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL)) {
            final Editable content = getEditable();
            if (content != null) {
                int start = android.text.Selection.getSelectionStart(content), end = android.text.Selection.getSelectionEnd(content);
                if (start >= 0 && end >= 0) {
                    int from = Math.min(start, end), to = Math.max(start, end);
                    if (from == to) {  /* one character, as the app counts them: a grapheme */
                        java.text.BreakIterator characters = java.text.BreakIterator.getCharacterInstance();
                        characters.setText(content.toString());
                        if (code == KeyEvent.KEYCODE_DEL && from > 0) {
                            from = characters.preceding(from);
                        } else if (code == KeyEvent.KEYCODE_FORWARD_DEL && to < content.length()) {
                            to = characters.following(to);
                        }
                    }
                    if (from >= 0 && to > from) {
                        content.delete(from, to);
                    }
                    mCommittedText = content.toString();
                }
            }
        }

        boolean sent = super.sendKeyEvent(event);
        watch(at, "sendKeyEvent " + (event.getAction() == KeyEvent.ACTION_DOWN ? "down " : "up ") + KeyEvent.keyCodeToString(code));
        return sent;
    }

    // Tells SDLActivity's keyboard watcher, if one is set, of a call from the keyboard and of the
    // editable after it: its text, selection and composing span, and what the app was last sent.
    protected void watch(long at, String call) {
        call = "@" + at + " " + call;  /* (System.nanoTime() when the call came in: the monotonic clock, as SDL's ticks) */
        SDLActivity.KeyboardWatcher watcher = SDLActivity.sKeyboardWatcher;
        if (watcher == null) {
            return;
        }
        final Editable content = getEditable();
        if (content == null) {
            watcher.call(call);
            return;
        }
        watcher.call(call + " -> \"" + content + "\" selection " + android.text.Selection.getSelectionStart(content) + ".." + android.text.Selection.getSelectionEnd(content) +
                     " composing " + getComposingSpanStart(content) + ".." + getComposingSpanEnd(content) + " committed \"" + mCommittedText + "\"");
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        long at = System.nanoTime();
        if (!super.commitText(text, newCursorPosition)) {
            watch(at, "commitText \"" + text + "\" " + newCursorPosition + " refused");
            return false;
        }
        updateText();
        watch(at, "commitText \"" + text + "\" " + newCursorPosition);
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        long at = System.nanoTime();
        if (!super.setComposingText(text, newCursorPosition)) {
            watch(at, "setComposingText \"" + text + "\" " + newCursorPosition + " refused");
            return false;
        }
        updateText();
        watch(at, "setComposingText \"" + text + "\" " + newCursorPosition);
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        long at = System.nanoTime();
        boolean ok = super.setComposingRegion(start, end);
        watch(at, "setComposingRegion " + start + " " + end + (ok ? "" : " refused"));
        return ok;
    }

    @Override
    public boolean finishComposingText() {
        long at = System.nanoTime();
        boolean ok = super.finishComposingText();
        watch(at, "finishComposingText" + (ok ? "" : " refused"));
        return ok;
    }

    @Override
    public boolean setSelection(int start, int end) {
        long at = System.nanoTime();
        boolean ok = super.setSelection(start, end);
        watch(at, "setSelection " + start + " " + end + (ok ? "" : " refused"));
        return ok;
    }

    // Android 14's replaceText, with which a keyboard puts in a suggestion picked from its strip:
    // BaseInputConnection edits the editable itself, so the app hears of it here.
    @Override
    public boolean replaceText(int start, int end, CharSequence text, int newCursorPosition, android.view.inputmethod.TextAttribute textAttribute) {
        long at = System.nanoTime();
        if (!super.replaceText(start, end, text, newCursorPosition, textAttribute)) {
            watch(at, "replaceText " + start + " " + end + " \"" + text + "\" " + newCursorPosition + " refused");
            return false;
        }
        updateText();
        watch(at, "replaceText " + start + " " + end + " \"" + text + "\" " + newCursorPosition);
        return true;
    }

    // The same deletion counted in code points, which BaseInputConnection would also apply to the
    // editable itself: it goes through deleteSurroundingText, counted in UTF-16 units.
    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        final Editable content = getEditable();
        if (content == null) {
            return deleteSurroundingText(beforeLength, afterLength);
        }
        int cursor = Math.max(0, android.text.Selection.getSelectionStart(content)), before = 0, after = 0;
        for (int i = cursor, n = 0; i > 0 && n < beforeLength; n++) {
            int step = Character.charCount(Character.codePointBefore(content, i));
            i -= step;
            before += step;
        }
        if (before < beforeLength) {
            before += beforeLength - before;  /* (past the editable's start: the app's text before typing began) */
        }
        for (int i = cursor, n = 0; i < content.length() && n < afterLength; n++) {
            int step = Character.charCount(Character.codePointAt(content, i));
            i += step;
            after += step;
        }
        return deleteSurroundingText(before, after);
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        long at = System.nanoTime();
        // Workaround to capture backspace key. Ref: http://stackoverflow.com/questions>/14560344/android-backspace-in-webview-baseinputconnection
        // and https://bugzilla.libsdl.org/show_bug.cgi?id=2265
        if (beforeLength > 0 && afterLength == 0) {
            // backspace(s): one for each character the keyboard deletes from the editable, which
            // loses them too, so that what it later composes and corrects there matches what the
            // app was sent; and one for each it reaches past the editable's start, into what the
            // app had before typing began
            final Editable content = getEditable();
            int cursor = content != null ? Math.max(0, android.text.Selection.getSelectionStart(content)) : 0;
            int held = Math.min(cursor, beforeLength);
            int backspaces = (content != null ? Character.codePointCount(content, cursor - held, cursor) : 0) + (beforeLength - held);
            while (backspaces-- > 0) {
                nativeGenerateScancodeForUnichar('\b');
            }
            if (content != null) {
                super.deleteSurroundingText(held, 0);
                mCommittedText = content.toString();
            }
            watch(at, "deleteSurroundingText " + beforeLength + " " + afterLength);
            return true;
       }

        if (!super.deleteSurroundingText(beforeLength, afterLength)) {
            watch(at, "deleteSurroundingText " + beforeLength + " " + afterLength + " refused");
            return false;
        }
        updateText();
        watch(at, "deleteSurroundingText " + beforeLength + " " + afterLength);
        return true;
    }

    protected void updateText() {
        final Editable content = getEditable();
        if (content == null) {
            return;
        }

        String text = content.toString();
        int compareLength = Math.min(text.length(), mCommittedText.length());
        int matchLength, offset;

        /* Backspace over characters that are no longer in the string */
        for (matchLength = 0; matchLength < compareLength; ) {
            int codePoint = mCommittedText.codePointAt(matchLength);
            if (codePoint != text.codePointAt(matchLength)) {
                break;
            }
            matchLength += Character.charCount(codePoint);
        }
        /* FIXME: This doesn't handle graphemes, like '🌬️' */
        for (offset = matchLength; offset < mCommittedText.length(); ) {
            int codePoint = mCommittedText.codePointAt(offset);
            nativeGenerateScancodeForUnichar('\b');
            offset += Character.charCount(codePoint);
        }

        if (matchLength < text.length()) {
            String pendingText = text.subSequence(matchLength, text.length()).toString();
            // (a key for each character, but none for a long piece such as a paste: two events a character
            // would fill SDL's event queue, and the text, sent after them, would be dropped)
            if (!SDLActivity.dispatchingKeyEvent() && pendingText.length() <= 64) {
                for (offset = 0; offset < pendingText.length(); ) {
                    int codePoint = pendingText.codePointAt(offset);
                    /* A line break alone is the Return key; within a longer piece, such as a paste, it is
                       text, and a Return key for it would add a second one, or send what is typed */
                    boolean alone = pendingText.length() == 1;
                    if (codePoint == '\n' && alone) {
                        if (SDLActivity.onNativeSoftReturnKey()) {
                            return;
                        }
                    }
                    /* Higher code points don't generate simulated scancodes */
                    if (codePoint > 0 && codePoint < 128 && (codePoint != '\n' || alone)) {
                        nativeGenerateScancodeForUnichar((char)codePoint);
                    }
                    offset += Character.charCount(codePoint);
                }
            }
            SDLInputConnection.nativeCommitText(pendingText, 0);
        }
        mCommittedText = text;
    }

    // An image the keyboard puts in (with SDL_HINT_ANDROID_TEXT_INPUT_IMAGES), such as a picture from
    // its clipboard: copied to a file in the app's cache folder, which reaches the app as a dropped file.
    @Override
    public boolean commitContent(android.view.inputmethod.InputContentInfo info, int flags, android.os.Bundle opts) {
        long at = System.nanoTime();
        android.content.ClipDescription description = info.getDescription();
        String type = description.getMimeTypeCount() > 0 ? description.getMimeType(0) : "";
        String ending = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
        java.io.File folder = new java.io.File(SDL.getContext().getCacheDir(), "keyboard");
        java.io.File file = new java.io.File(folder, "image-" + System.currentTimeMillis() + (ending != null ? "." + ending : ""));
        boolean asked = (flags & android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0;
        try {
            if (asked) {
                info.requestPermission();
            }
            folder.mkdirs();
            try (java.io.InputStream in = SDL.getContext().getContentResolver().openInputStream(info.getContentUri());
                 java.io.OutputStream out = new java.io.FileOutputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                }
            }
        } catch (Exception e) {
            file.delete();
            watch(at, "commitContent " + type + " failed: " + e);
            return false;
        } finally {
            if (asked) {
                info.releasePermission();
            }
        }
        SDLActivity.onNativeDropFile(file.getPath());
        watch(at, "commitContent " + type + " -> " + file.getPath());
        return true;
    }

    public static native void nativeCommitText(String text, int newCursorPosition);

    public static native void nativeGenerateScancodeForUnichar(char c);
}

