package dev.omar.plugin.iconsrepo.ui.viewmodel;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;

import dev.omar.plugin.iconsrepo.data.models.IconModel;
import dev.omar.plugin.iconsrepo.data.repository.IconRepository;

public class MainViewModel extends ViewModel {

    private final IconRepository iconRepository;
    private final MutableLiveData<MainUiState> uiState = new MutableLiveData<>(MainUiState.loading());

    public MainViewModel(@NonNull IconRepository iconRepository) {
        this.iconRepository = iconRepository;
    }

    @NonNull
    public LiveData<MainUiState> getUiState() {
        return uiState;
    }

    public void loadIcons() {
        uiState.setValue(MainUiState.loading());
        iconRepository.loadAllIconsAsync(new IconRepository.LoadIconsCallback() {
            @Override
            public void onLoaded(List<IconModel> list) {
                if (list == null || list.isEmpty()) {
                    uiState.setValue(MainUiState.empty());
                } else {
                    uiState.setValue(MainUiState.success(list));
                }
            }

            @Override
            public void onError(Throwable th) {
                String msg = th != null && th.getMessage() != null ? th.getMessage() : "Unknown error";
                uiState.setValue(MainUiState.error(msg));
            }
        });
    }
}
