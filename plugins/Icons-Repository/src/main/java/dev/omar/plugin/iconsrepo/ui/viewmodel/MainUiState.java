package dev.omar.plugin.iconsrepo.ui.viewmodel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import dev.omar.plugin.iconsrepo.data.models.IconModel;

public class MainUiState {

    public enum Status {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR
    }

    private final Status status;
    private final List<IconModel> icons;
    private final String errorMessage;

    private MainUiState(@NonNull Status status, @NonNull List<IconModel> icons, @Nullable String errorMessage) {
        this.status = status;
        this.icons = icons;
        this.errorMessage = errorMessage;
    }

    public static MainUiState loading() {
        return new MainUiState(Status.LOADING, Collections.emptyList(), null);
    }

    public static MainUiState success(@NonNull List<IconModel> icons) {
        if (icons.isEmpty()) {
            return empty();
        }
        return new MainUiState(Status.SUCCESS, icons, null);
    }

    public static MainUiState empty() {
        return new MainUiState(Status.EMPTY, Collections.emptyList(), null);
    }

    public static MainUiState error(@NonNull String errorMessage) {
        return new MainUiState(Status.ERROR, Collections.emptyList(), errorMessage);
    }

    @NonNull
    public Status getStatus() {
        return status;
    }

    @NonNull
    public List<IconModel> getIcons() {
        return icons;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isLoading() {
        return status == Status.LOADING;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isEmpty() {
        return status == Status.EMPTY;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }
}
