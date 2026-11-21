package dev.omar.plugin.iconsrepo.data.importer;

import com.itsaky.androidide.plugins.extensions.IProject;
import java.io.File;
import java.util.List;

public interface ImportPathResolver {
    
    File getDrawableDirectory(IProject project);

    List<File> getDrawableDirectories(IProject project);
}
