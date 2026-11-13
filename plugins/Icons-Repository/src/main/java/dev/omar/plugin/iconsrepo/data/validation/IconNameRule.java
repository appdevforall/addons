package dev.omar.plugin.iconsrepo.data.validation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class IconNameRule implements ValidationRule {

    /*
     * Regex rules:
     * ^[a-z]          -> must start with a lowercase letter
     * (?!.*__)        -> must NOT contain double underscore
     * [a-z0-9_]*      -> allowed characters
     * (?<!_)$         -> must NOT end with underscore
     */
    private static final String PATTERN = "^[a-z](?!.*__)[a-z0-9_]*(?<!_)$";

    // Common reserved keywords (Java/Kotlin)
    private static final Set<String> RESERVED_KEYWORDS = new HashSet<>(Arrays.asList(
            "class", "interface", "enum", "void", "int", "float", "double",
            "long", "short", "boolean", "char", "if", "else", "switch",
            "case", "default", "for", "while", "do", "try", "catch",
            "finally", "return", "new", "public", "private", "protected",
            "static", "final", "null", "true", "false", "package", "import"
    ));

    @Override
    public boolean isValid(String input) {
        if (input == null) return false;

        input = input.trim();

        // Length check (optional but practical)
        if (input.isEmpty() || input.length() > 100) return false;

        // Pattern check
        if (!input.matches(PATTERN)) return false;

        // Reserved keywords check
        if (RESERVED_KEYWORDS.contains(input)) return false;

        return true;
    }

    @Override
    public String getErrorMessage() {
        return "Invalid name. Use lowercase letters, digits, or underscore. " +
               "Must start with a letter, cannot end with '_', contain '__', or be a reserved keyword.";
    }
}