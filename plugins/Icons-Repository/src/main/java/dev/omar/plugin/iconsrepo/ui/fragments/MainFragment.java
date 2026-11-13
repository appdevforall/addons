package dev.omar.plugin.iconsrepo.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.itsaky.androidide.plugins.extensions.IProject;
import com.itsaky.androidide.plugins.services.IdeProjectService;

import dev.omar.plugin.iconsrepo.Main;
import dev.omar.plugin.iconsrepo.R;
import dev.omar.plugin.iconsrepo.databinding.FragmentMainBinding;
import dev.omar.plugin.iconsrepo.data.models.IconModel;
import dev.omar.plugin.iconsrepo.data.repository.IconRepository;
import dev.omar.plugin.iconsrepo.ui.adapter.IconsAdapter;
import dev.omar.plugin.iconsrepo.ui.dialog.IconImportDialogManager;
import dev.omar.plugin.iconsrepo.ui.viewmodel.MainUiState;
import dev.omar.plugin.iconsrepo.ui.viewmodel.MainViewModel;
import dev.omar.plugin.iconsrepo.ui.viewmodel.MainViewModelFactory;

public class MainFragment extends PluginFragment {

    private FragmentMainBinding binding;
    private MainViewModel viewModel;
    private IconsAdapter adapter;
    private IconImportDialogManager dialogManager;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentMainBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    @MainThread
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initViewsAndAdapter();
        initViewModel();
        observeUiState();

        viewModel.loadIcons();
    }

    private void initViewsAndAdapter() {
        dialogManager = new IconImportDialogManager(requireContext(), getLayoutInflater());

        adapter = new IconsAdapter();
        adapter.setOnItemClickListener((model, itemView, position) -> showImportDialog(model));

        setupRecyclerView();

        binding.searchEditText.addTextChangedListener(adapter);
        binding.iconsRecyclerView.setAdapter(adapter);
    }

    private void setupRecyclerView() {
        final RecyclerView recyclerView = binding.iconsRecyclerView;
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 4));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
    }

    private void initViewModel() {
        IconRepository repository = Main.getInstance().getIconRepository();
        MainViewModelFactory factory = new MainViewModelFactory(repository);
        viewModel = new ViewModelProvider(this, factory).get(MainViewModel.class);
    }

    private void observeUiState() {
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::renderUiState);
    }

    private void renderUiState(@NonNull MainUiState state) {
        switch (state.getStatus()) {
            case LOADING:
                showLoadingState();
                break;
            case SUCCESS:
                showSuccessState(state);
                break;
            case EMPTY:
                showEmptyState(getString(R.string.no_icons_found));
                break;
            case ERROR:
                showEmptyState("Error: " + (state.getErrorMessage() != null ? state.getErrorMessage() : ""));
                break;
        }
    }

    private void showLoadingState() {
        binding.loadingProgressBar.setVisibility(View.VISIBLE);
        binding.iconsRecyclerView.setVisibility(View.GONE);
        binding.emptyStateText.setVisibility(View.GONE);
        binding.searchEditText.setEnabled(false);
    }

    private void showSuccessState(@NonNull MainUiState state) {
        binding.loadingProgressBar.setVisibility(View.GONE);
        binding.emptyStateText.setVisibility(View.GONE);
        binding.iconsRecyclerView.setVisibility(View.VISIBLE);
        binding.searchEditText.setEnabled(true);

        adapter.setOriginalList(state.getIcons());
        binding.searchInputLayout.setHint("Search in " + state.getIcons().size() + " icon");
    }

    private void showEmptyState(String message) {
        binding.loadingProgressBar.setVisibility(View.GONE);
        binding.iconsRecyclerView.setVisibility(View.GONE);
        binding.emptyStateText.setVisibility(View.VISIBLE);
        binding.emptyStateText.setText(message);
        binding.searchEditText.setEnabled(true);
    }

    private void showImportDialog(@NonNull IconModel model) {
        IdeProjectService projectService = Main.getInstance().getProjectService();
        IProject currentProject = projectService != null ? projectService.getCurrentProject() : null;
        dialogManager.showImportDialog(model, currentProject);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
