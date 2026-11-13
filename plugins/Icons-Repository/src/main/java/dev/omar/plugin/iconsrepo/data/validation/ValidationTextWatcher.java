package dev.omar.plugin.iconsrepo.data.validation;

import android.text.Editable;
import android.text.TextWatcher;

import com.google.android.material.textfield.TextInputEditText;

import dev.omar.plugin.iconsrepo.data.validation.ValidationRule;

import java.util.List;

public class ValidationTextWatcher implements TextWatcher {

    private final TextInputEditText inputEditText;
    private final List<ValidationRule> rules;
    private final OnAfterValidOperation listener;

    public ValidationTextWatcher(TextInputEditText inputEditText, List<ValidationRule> rules, OnAfterValidOperation listener) {
        this.inputEditText = inputEditText;
        this.rules = rules;
        this.listener = listener;
    }

    public ValidationTextWatcher(TextInputEditText inputEditText, List<ValidationRule> rules) {
        this(inputEditText,rules,null);
    }

    @Override
    public void beforeTextChanged(CharSequence text, int start, int end, int length) {

    }

    @Override
    public void onTextChanged(CharSequence text, int start, int end, int length) {
    }

    @Override
    public void afterTextChanged(Editable editable) {
        boolean state = Validator.validate(inputEditText, rules);
        if(listener != null){
            listener.afterValidation(editable.toString(),state);
        }
    }

    public interface OnAfterValidOperation {
        void afterValidation(String text, boolean state);
    }
}
