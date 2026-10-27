package com.example.ariseosleeper

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.textclassifier.TextClassifier
import android.widget.EditText

/** Visible-password semantics discourage prediction even for keyboards that ignore
 * NO_SUGGESTIONS on ordinary prose. Apply to library entry as well as answers so
 * adding a passage does not train the keyboard before the first challenge. */
internal object VerseTextInput {
    fun configure(field: EditText) = with(field) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        transformationMethod = null // Keep the verse readable for corrections.
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_FULLSCREEN
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        setTextClassifier(TextClassifier.NO_OP)
    }
}
