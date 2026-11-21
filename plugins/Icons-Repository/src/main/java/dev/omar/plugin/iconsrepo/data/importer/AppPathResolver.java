package dev.omar.plugin.iconsrepo.data.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.itsaky.androidide.plugins.extensions.IModule;
import com.itsaky.androidide.plugins.extensions.IProject;
import com.itsaky.androidide.plugins.extensions.SourceSet;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppPathResolver implements ImportPathResolver {

    public static final String TARGET_ALL_MODULES = "ALL";

    private final String targetModuleName;

    public AppPathResolver() {
        this(null);
    }

    public AppPathResolver(@Nullable String targetModuleName) {
        this.targetModuleName = targetModuleName;
    }

    @Override
    public File getDrawableDirectory(IProject project) {
        List<File> dirs = getDrawableDirectories(project);
        if (!dirs.isEmpty()) {
            return dirs.get(0);
        }
        File fallback = new File(project.getRootDir(), "app/src/main/res/drawable");
        if (!fallback.exists() && fallback.getParentFile() != null) {
            fallback.mkdirs();
        }
        return fallback;
    }

    @Override
    public List<File> getDrawableDirectories(IProject project) {
        if (project == null) {
            return Collections.emptyList();
        }

        List<IModule> availableModules = getAvailableModules(project);
        List<File> targetDirectories = new ArrayList<>();

        boolean isAllModules = targetModuleName == null 
                || TARGET_ALL_MODULES.equalsIgnoreCase(targetModuleName)
                || targetModuleName.startsWith("All modules")
                || targetModuleName.startsWith("جميع");

        for (IModule module : availableModules) {
            if (isAllModules || matchesModuleName(module, targetModuleName)) {
                File drawableDir = getDrawableDirForModule(module);
                if (drawableDir != null) {
                    if (!drawableDir.exists() && drawableDir.getParentFile() != null && drawableDir.getParentFile().canWrite()) {
                        drawableDir.mkdirs();
                    }
                    targetDirectories.add(drawableDir);
                }
            }
        }

        if (targetDirectories.isEmpty()) {
            File fallback = new File(project.getRootDir(), "app/src/main/res/drawable");
            if (!fallback.exists() && fallback.getParentFile() != null && fallback.getParentFile().canWrite()) {
                fallback.mkdirs();
            }
            targetDirectories.add(fallback);
        }

        return targetDirectories;
    }

    @NonNull
    public static List<IModule> getAvailableModules(@Nullable IProject project) {
        return ModuleDiscoveryEngine.discoverModules(project);
    }

    private static boolean matchesModuleName(@NonNull IModule module, @NonNull String target) {
        return ModuleDiscoveryEngine.matchesModuleName(module, target);
    }

    @Nullable
    public static File getDrawableDirForModule(@NonNull IModule module) {
        if (module.getSourceSets() != null) {
            for (SourceSet source : module.getSourceSets()) {
                if ("main".equalsIgnoreCase(source.getName())) {
                    if (source.getResourceDirs() != null) {
                        for (File resDir : source.getResourceDirs()) {
                            if (resDir != null && ("res".equalsIgnoreCase(resDir.getName()) || resDir.getPath().endsWith("res"))) {
                                return new File(resDir, "drawable");
                            }
                        }
                    }
                }
            }
        }
        File mainResDir = new File(module.getProjectDir(), "src/main/res");
        return new File(mainResDir, "drawable");
    }
}
