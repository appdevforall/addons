package dev.omar.plugin.iconsrepo.data.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.itsaky.androidide.plugins.extensions.IModule;
import com.itsaky.androidide.plugins.extensions.IProject;
import com.itsaky.androidide.plugins.extensions.ModuleType;
import com.itsaky.androidide.plugins.extensions.SourceSet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModuleDiscoveryEngine {

    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("include\\s*\\(?\\s*([^\\)]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_MODULE_PATTERN =
            Pattern.compile("['\"](:[a-zA-Z0-9_:-]+)['\"]");

    @NonNull
    public static List<IModule> discoverModules(@Nullable IProject project) {
        if (project == null) {
            return Collections.emptyList();
        }

        Map<String, IModule> moduleMap = new LinkedHashMap<>();
        File rootDir = project.getRootDir();
        if (rootDir == null) {
            return Collections.emptyList();
        }

        String rootModuleName = ":" + getProjectName(project, rootDir);

        try {
            List<IModule> ideModules = project.getModules();
            if (ideModules != null) {
                for (IModule module : ideModules) {
                    if (module != null && module.getName() != null) {
                        String name = module.getName();
                        if ("app".equalsIgnoreCase(name) && !new File(rootDir, "app").exists() && isValidAndroidModuleDir(rootDir)) {
                            moduleMap.put(rootModuleName, createSyntheticModule(rootModuleName, rootDir));
                        } else {
                            String normName = normalizeModuleName(name);
                            moduleMap.put(normName, module);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        Map<String, File> settingsModules = parseSettingsGradle(rootDir);
        for (Map.Entry<String, File> entry : settingsModules.entrySet()) {
            String normName = entry.getKey();
            File moduleDir = entry.getValue();
            if (!moduleMap.containsKey(normName) && isValidAndroidModuleDir(moduleDir)) {
                moduleMap.put(normName, createSyntheticModule(normName, moduleDir));
            }
        }

        Map<String, File> fsModules = new LinkedHashMap<>();
        scanFileSystemModules(rootDir, rootDir, fsModules, 5);
        for (Map.Entry<String, File> entry : fsModules.entrySet()) {
            String normName = entry.getKey();
            File moduleDir = entry.getValue();
            if (!moduleMap.containsKey(normName)) {
                moduleMap.put(normName, createSyntheticModule(normName, moduleDir));
            }
        }

        if (isValidAndroidModuleDir(rootDir) && !moduleMap.containsKey(rootModuleName)) {
            boolean hasSubmodules = false;
            for (String key : moduleMap.keySet()) {
                if (!key.equalsIgnoreCase(rootModuleName)) {
                    hasSubmodules = true;
                    break;
                }
            }
            if (!hasSubmodules) {
                moduleMap.put(rootModuleName, createSyntheticModule(rootModuleName, rootDir));
            }
        }

        if (moduleMap.isEmpty()) {
            File defaultAppDir = new File(rootDir, "app");
            if (defaultAppDir.exists()) {
                moduleMap.put(":app", createSyntheticModule(":app", defaultAppDir));
            } else {
                moduleMap.put(rootModuleName, createSyntheticModule(rootModuleName, rootDir));
            }
        }

        List<IModule> result = new ArrayList<>(moduleMap.values());
        result.sort(Comparator.comparing(m -> normalizeModuleName(m.getName())));
        return result;
    }

    @NonNull
    public static String getProjectName(@Nullable IProject project, @NonNull File rootDir) {
        if (project != null && project.getName() != null && !project.getName().trim().isEmpty() && !"app".equalsIgnoreCase(project.getName())) {
            return project.getName().trim();
        }
        return rootDir.getName();
    }

    @NonNull
    public static String normalizeModuleName(@NonNull String rawName) {
        String trimmed = rawName.trim();
        return trimmed.startsWith(":") ? trimmed : ":" + trimmed;
    }

    public static boolean matchesModuleName(@NonNull IModule module, @NonNull String target) {
        String modNorm = normalizeModuleName(module.getName());
        String targetNorm = normalizeModuleName(target);

        if (modNorm.equalsIgnoreCase(targetNorm)) {
            return true;
        }

        int lastColonMod = modNorm.lastIndexOf(':');
        String leafMod = lastColonMod >= 0 ? modNorm.substring(lastColonMod + 1) : modNorm;

        int lastColonTarget = targetNorm.lastIndexOf(':');
        String leafTarget = lastColonTarget >= 0 ? targetNorm.substring(lastColonTarget + 1) : targetNorm;

        return leafMod.equalsIgnoreCase(leafTarget);
    }

    private static Map<String, File> parseSettingsGradle(File rootDir) {
        Map<String, File> result = new LinkedHashMap<>();
        File settingsGroovy = new File(rootDir, "settings.gradle");
        File settingsKts = new File(rootDir, "settings.gradle.kts");

        File settingsFile = settingsGroovy.exists() ? settingsGroovy : (settingsKts.exists() ? settingsKts : null);
        if (settingsFile == null) {
            return result;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(settingsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("//") || trimmedLine.startsWith("/*")) {
                    continue;
                }
                Matcher includeMatcher = INCLUDE_PATTERN.matcher(trimmedLine);
                if (includeMatcher.find()) {
                    String includeBody = includeMatcher.group(1);
                    if (includeBody != null) {
                        Matcher moduleMatcher = QUOTED_MODULE_PATTERN.matcher(includeBody);
                        while (moduleMatcher.find()) {
                            String rawModule = moduleMatcher.group(1);
                            if (rawModule != null) {
                                String modName = normalizeModuleName(rawModule);
                                String relativePath = modName.substring(1).replace(':', File.separatorChar);
                                File modDir = new File(rootDir, relativePath);
                                result.put(modName, modDir);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static void scanFileSystemModules(File currentDir, File rootDir, Map<String, File> discovered, int depth) {
        if (currentDir == null || !currentDir.isDirectory() || depth <= 0) {
            return;
        }

        String name = currentDir.getName();
        if (name.startsWith(".") || "build".equalsIgnoreCase(name) || "bin".equalsIgnoreCase(name) 
                || "out".equalsIgnoreCase(name) || "node_modules".equalsIgnoreCase(name)) {
            return;
        }

        if (!currentDir.equals(rootDir) && isValidAndroidModuleDir(currentDir)) {
            try {
                String relative = rootDir.toPath().relativize(currentDir.toPath()).toString().replace('\\', '/');
                String modName = ":" + relative.replace('/', ':');
                discovered.put(modName, currentDir);
            } catch (Exception ignored) {
            }
        }

        File[] children = currentDir.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                scanFileSystemModules(child, rootDir, discovered, depth - 1);
            }
        }
    }

    public static boolean isValidAndroidModuleDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File buildGradle = new File(dir, "build.gradle");
        File buildGradleKts = new File(dir, "build.gradle.kts");
        File srcMain = new File(dir, "src/main");

        return (buildGradle.exists() || buildGradleKts.exists()) && srcMain.exists();
    }

    public static IModule createSyntheticModule(@NonNull String moduleName, @NonNull File moduleDir) {
        return new IModule() {
            @NonNull
            @Override
            public String getName() {
                return moduleName;
            }

            @NonNull
            @Override
            public ModuleType getType() {
                return ModuleType.ANDROID_APP;
            }

            @NonNull
            @Override
            public File getProjectDir() {
                return moduleDir;
            }

            @NonNull
            @Override
            public List<SourceSet> getSourceSets() {
                File mainDir = new File(moduleDir, "src/main");
                File javaDir = new File(mainDir, "java");
                File resDir = new File(mainDir, "res");
                return Collections.singletonList(
                        new SourceSet("main", List.of(javaDir), List.of(resDir))
                );
            }
        };
    }
}
