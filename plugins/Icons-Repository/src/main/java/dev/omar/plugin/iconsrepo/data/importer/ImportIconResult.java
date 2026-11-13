package dev.omar.plugin.iconsrepo.data.importer;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public record ImportIconResult(boolean isSuccess, String message) {
    public ImportIconResult(boolean isSucces) {
        this(isSucces, null);
    }

    @NonNull
    @Contract(value = " -> new", pure = true)
    public static ImportIconResult success() {
        return new ImportIconResult(true);
    }

    @NonNull
    @Contract(value = "_ -> new", pure = true)
    public static ImportIconResult error(String error) {
        return new ImportIconResult(false, error);
    }

}
