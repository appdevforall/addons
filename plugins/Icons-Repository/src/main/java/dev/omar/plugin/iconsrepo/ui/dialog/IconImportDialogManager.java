package dev.omar.plugin.iconsrepo.ui.dialog;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.PictureDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.itsaky.androidide.plugins.extensions.IModule;
import com.itsaky.androidide.plugins.extensions.IProject;

import java.util.ArrayList;
import java.util.List;

import dev.omar.plugin.iconsrepo.data.importer.AppPathResolver;
import dev.omar.plugin.iconsrepo.data.importer.ImportIconResult;
import dev.omar.plugin.iconsrepo.data.models.IconModel;
import dev.omar.plugin.iconsrepo.data.validation.HexColorRule;
import dev.omar.plugin.iconsrepo.data.validation.IconNameRule;
import dev.omar.plugin.iconsrepo.data.validation.ValidationTextWatcher;
import dev.omar.plugin.iconsrepo.data.validation.Validator;
import dev.omar.plugin.iconsrepo.databinding.DialogImportIconBinding;
import dev.omar.plugin.iconsrepo.domain.IconImportUseCase;
import dev.omar.plugin.iconsrepo.utils.DynamicColorHelper;
import dev.omar.plugin.iconsrepo.utils.ImageUtils;

public class IconImportDialogManager {

    private final Context context;
    private final LayoutInflater inflater;
    private final IconImportUseCase importUseCase;
    private final List<String> moduleTargetNames = new ArrayList<>();

    public IconImportDialogManager(@NonNull Context context, @NonNull LayoutInflater inflater) {
        this(context, inflater, new IconImportUseCase());
    }

    public IconImportDialogManager(
            @NonNull Context context,
            @NonNull LayoutInflater inflater,
            @NonNull IconImportUseCase importUseCase) {
        this.context = context;
        this.inflater = inflater;
        this.importUseCase = importUseCase;
    }

    public void showImportDialog(
            @NonNull IconModel model,
            @Nullable IProject currentProject) {

        final DialogImportIconBinding binding = DialogImportIconBinding.inflate(inflater);

        setupInputName(binding, model);
        setupModulesList(binding, currentProject);
        setupColorsList(binding);
        setupIconPreview(binding, model);

        final MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(context);
        dialogBuilder.setTitle("Import vector icon");
        dialogBuilder.setView(binding.getRoot());
        dialogBuilder.setPositiveButton("Import", null);
        dialogBuilder.setNegativeButton("Cancel", null);

        final AlertDialog alertDialog = dialogBuilder.show();

        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (handleImportSubmitted(binding, currentProject, model)) {
                alertDialog.dismiss();
            }
        });
    }

    private void setupInputName(@NonNull DialogImportIconBinding binding, @NonNull IconModel model) {
        String defaultName = "ic_" + model.getIconName().replace("-", "_");
        binding.inputName.setText(defaultName);
        binding.inputName.addTextChangedListener(
                new ValidationTextWatcher(binding.inputName, List.of(new IconNameRule())));
    }

    private void setupModulesList(@NonNull DialogImportIconBinding binding, @Nullable IProject currentProject) {
        moduleTargetNames.clear();
        List<IModule> availableModules = AppPathResolver.getAvailableModules(currentProject);

        List<String> displayNames = new ArrayList<>();
        String allModulesTitle = "All modules (" + availableModules.size() + ")";
        displayNames.add(allModulesTitle);
        moduleTargetNames.add(AppPathResolver.TARGET_ALL_MODULES);

        for (IModule module : availableModules) {
            String name = module.getName();
            String formattedName = name.startsWith(":") ? name : ":" + name;
            displayNames.add(formattedName);
            moduleTargetNames.add(name);
        }

        binding.txtListModules.setAdapter(
                new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, displayNames));

        // Default to "All modules" (position 0)
        binding.txtListModules.setSelection(0);
    }

    private void setupIconPreview(@NonNull DialogImportIconBinding binding, @NonNull IconModel model) {
        try {
            int defaultColor = DynamicColorHelper.resolveDynamicColor(
                    context,
                    DynamicColorHelper.getDefaultColorModel());
            PictureDrawable pd = new PictureDrawable(model.getSvgIcon().renderToPicture());
            Bitmap rawBitmap = ImageUtils.drawable2Bitmap(pd);
            Bitmap coloredBitmap = ImageUtils.drawColor(rawBitmap, defaultColor);
            binding.iconPreview.setImageBitmap(coloredBitmap);
        } catch (Exception ignored) {
            // Fallback layout preview
        }
    }

    private void setupColorsList(@NonNull DialogImportIconBinding binding) {
        int defaultColor = DynamicColorHelper.resolveDynamicColor(
                context,
                DynamicColorHelper.getDefaultColorModel());

        List<String> colorsNames = DynamicColorHelper.getDisplayNames();
        colorsNames.add(0, "None");
        colorsNames.add(1, "Custom");

        binding.txtListColors.setAdapter(
                new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, colorsNames));

        binding.txtListColors.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                binding.inputColorLayout.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
                updatePreviewColor(binding, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        binding.inputColor.addTextChangedListener(
                new ValidationTextWatcher(
                        binding.inputColor,
                        List.of(new HexColorRule()),
                        (text, isValid) -> {
                            if (isValid) {
                                try {
                                    int parsedColor = Color.parseColor(text);
                                    binding.iconPreview.setColorFilter(parsedColor, PorterDuff.Mode.SRC_IN);
                                } catch (Exception ignored) {
                                }
                            }
                        }));

        // Initial default color filter
        binding.iconPreview.setColorFilter(defaultColor, PorterDuff.Mode.SRC_IN);
    }

    private void updatePreviewColor(@NonNull DialogImportIconBinding binding, int position) {
        try {
            switch (position) {
                case 0 -> binding.iconPreview.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
                case 1 -> {
                    String colorText = binding.inputColor.getText() != null ? binding.inputColor.getText().toString() : "";
                    int color = Color.parseColor(colorText);
                    binding.iconPreview.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                }
                default -> {
                    int colorAttrIndex = Math.max(0, position - 2);
                    DynamicColorHelper.ColorReferenceModel colorModel =
                            DynamicColorHelper.getDynamicColors().get(colorAttrIndex);
                    int resolvedColor = DynamicColorHelper.resolveDynamicColor(context, colorModel);
                    binding.iconPreview.setColorFilter(resolvedColor, PorterDuff.Mode.SRC_IN);
                }
            }
        } catch (Exception e) {
            binding.iconPreview.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
        }
    }

    private boolean handleImportSubmitted(
            @NonNull DialogImportIconBinding binding,
            @Nullable IProject currentProject,
            @NonNull IconModel model) {

        boolean isValidName = Validator.validate(binding.inputName, List.of(new IconNameRule()));
        int selectedColorPosition = binding.txtListColors.getSelectedItemPosition();

        boolean isValidColor = selectedColorPosition != 1
                || Validator.validate(binding.inputColor, List.of(new HexColorRule()));

        if (!isValidName || !isValidColor) {
            return false;
        }

        try {
            String name = binding.inputName.getText() != null ? binding.inputName.getText().toString() : "";
            String colorTint = resolveColorValue(binding, selectedColorPosition);

            int selectedModulePos = binding.txtListModules.getSelectedItemPosition();
            String targetModule = (selectedModulePos >= 0 && selectedModulePos < moduleTargetNames.size())
                    ? moduleTargetNames.get(selectedModulePos)
                    : AppPathResolver.TARGET_ALL_MODULES;

            ImportIconResult result = importUseCase.execute(currentProject, model, name, colorTint, targetModule);
            if (!result.isSuccess()) {
                Toast.makeText(context, "Error: " + result.message(), Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        } catch (Exception err) {
            Toast.makeText(context, "Error: " + err.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Nullable
    private String resolveColorValue(@NonNull DialogImportIconBinding binding, int selectedPosition) {
        if (selectedPosition > 1) {
            DynamicColorHelper.ColorReferenceModel colorModel =
                    DynamicColorHelper.getDynamicColors().get(selectedPosition - 2);
            return colorModel.getRef();
        } else if (selectedPosition == 1 && binding.inputColor.getText() != null) {
            return binding.inputColor.getText().toString();
        }
        return null;
    }
}
