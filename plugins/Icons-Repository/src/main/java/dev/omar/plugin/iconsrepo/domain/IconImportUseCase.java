package dev.omar.plugin.iconsrepo.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.itsaky.androidide.plugins.extensions.IProject;

import dev.omar.plugin.iconsrepo.data.importer.AppPathResolver;
import dev.omar.plugin.iconsrepo.data.importer.AssetIconSource;
import dev.omar.plugin.iconsrepo.data.importer.IconImporter;
import dev.omar.plugin.iconsrepo.data.importer.ImportIconResult;
import dev.omar.plugin.iconsrepo.data.importer.ImportParams;
import dev.omar.plugin.iconsrepo.data.importer.SvgToVectorImporter;
import dev.omar.plugin.iconsrepo.data.models.IconModel;

public class IconImportUseCase {

    private final IconImporter importer;

    public IconImportUseCase() {
        this(new SvgToVectorImporter());
    }

    public IconImportUseCase(@NonNull IconImporter importer) {
        this.importer = importer;
    }

    @NonNull
    public ImportIconResult execute(
            @Nullable IProject project,
            @NonNull IconModel iconModel,
            @NonNull String name,
            @Nullable String colorTint) {
        return execute(project, iconModel, name, colorTint, AppPathResolver.TARGET_ALL_MODULES);
    }

    @NonNull
    public ImportIconResult execute(
            @Nullable IProject project,
            @NonNull IconModel iconModel,
            @NonNull String name,
            @Nullable String colorTint,
            @Nullable String targetModuleName) {

        AppPathResolver pathResolver = new AppPathResolver(targetModuleName);
        ImportParams params = new ImportParams(
                project,
                new AssetIconSource(name, iconModel.getData()),
                pathResolver,
                targetModuleName
        );
        params.useTint(colorTint);

        return importer.importIcon(params);
    }
}
