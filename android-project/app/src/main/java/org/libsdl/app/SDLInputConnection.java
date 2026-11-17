package org.libsdl.app;

import android.content.*;
import android.os.Build;
import android.text.Editable;
import android.view.*;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;

class SDLInputConnection extends BaseInputConnection
{
    protected EditText mEditText;
    protected String mCommittedText = "";

    // The app's text before its cursor, which a new connection starts from, so that the keyboard
    // composes and corrects in its context (SDLActivity.restartTextInput)
    static volatile String sBefore = "";

    SDLInputConnection(View targetView, boolean fullEditor) {
        super(targetView, fullEditor);
        mEditText = new EditText(SDL.getContext());
        String before = sBefore;
        if (!before.isEmpty()) {
            mEditText.setText(before);
            mEditText.setSelection(before.length());
            mCommittedText = before;
        }
    }

    @Override
    public Editable getEditable() {
        return mEditText.getEditableText();
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
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

        return super.sendKeyEvent(event);
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (!super.commitText(text, newCursorPosition)) {
            return false;
        }
        updateText();
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        if (!super.setComposingText(text, newCursorPosition)) {
            return false;
        }
        updateText();
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
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
            return true;
       }

        if (!super.deleteSurroundingText(beforeLength, afterLength)) {
            return false;
        }
        updateText();
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
            if (!SDLActivity.dispatchingKeyEvent()) {
                for (offset = 0; offset < pendingText.length(); ) {
                    int codePoint = pendingText.codePointAt(offset);
                    if (codePoint == '\n') {
                        if (SDLActivity.onNativeSoftReturnKey()) {
                            return;
                        }
                    }
                    /* Higher code points don't generate simulated scancodes */
                    if (codePoint > 0 && codePoint < 128) {
                        nativeGenerateScancodeForUnichar((char)codePoint);
                    }
                    offset += Character.charCount(codePoint);
                }
            }
            SDLInputConnection.nativeCommitText(pendingText, 0);
        }
        mCommittedText = text;
    }

    public static native void nativeCommitText(String text, int newCursorPosition);

    public static native void nativeGenerateScancodeForUnichar(char c);
}

