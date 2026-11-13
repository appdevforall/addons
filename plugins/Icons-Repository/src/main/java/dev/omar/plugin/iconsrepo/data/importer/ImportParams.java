package dev.omar.plugin.iconsrepo.data.importer;

import com.itsaky.androidide.plugins.extensions.IProject;

public class ImportParams {

    public String tint;
    public IProject currentProject;
    public IconSource source;
    public ImportPathResolver dest;
    public String targetModule;

    public ImportParams(IProject currentProject, IconSource source, ImportPathResolver dest) {
        this(currentProject, source, dest, null);
    }

    public ImportParams(IProject currentProject, IconSource source, ImportPathResolver dest, String targetModule) {
        this.currentProject = currentProject;
        this.source = source;
        this.dest = dest;
        this.targetModule = targetModule;
    }

    public ImportParams useTint(String tint) {
        this.tint = tint;
        return this;
    }

    public boolean isUsedTint() {
        return tint != null;
    }
}
