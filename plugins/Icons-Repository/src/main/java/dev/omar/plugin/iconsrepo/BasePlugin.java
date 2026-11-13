package dev.omar.plugin.iconsrepo;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.itsaky.androidide.plugins.IPlugin;
import com.itsaky.androidide.plugins.PluginContext;
import com.itsaky.androidide.plugins.PluginLogger;
import com.itsaky.androidide.plugins.ServiceRegistry;
import com.itsaky.androidide.plugins.base.PluginFragmentHelper;
import com.itsaky.androidide.plugins.extensions.ContextMenuContext;
import com.itsaky.androidide.plugins.extensions.EditorTabExtension;
import com.itsaky.androidide.plugins.extensions.EditorTabItem;
import com.itsaky.androidide.plugins.extensions.FabAction;
import com.itsaky.androidide.plugins.extensions.MenuItem;
import com.itsaky.androidide.plugins.extensions.NavigationItem;
import com.itsaky.androidide.plugins.extensions.TabItem;
import com.itsaky.androidide.plugins.extensions.ToolbarAction;
import com.itsaky.androidide.plugins.extensions.UIExtension;

import com.itsaky.androidide.plugins.services.IdeProjectService;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public abstract class BasePlugin implements IPlugin, UIExtension, EditorTabExtension {

    private PluginContext context;
    private IdeProjectService projectService;
    
    
    @Override
    public boolean initialize(@NonNull PluginContext context) {
        try {
            this.context = context;
            this.projectService = getServices().get(IdeProjectService.class);
            if(projectService == null){
                throw new NullPointerException("IdeProjectService not available!");
            }
            
            onInitialized(context);
            return true;
        } catch (Exception err) {
            return false;
        }
    }
    
    public IdeProjectService getProjectService() {
    	return projectService;
    }
    
    public PluginContext getPluginContext() {
    	return context;
    }
    
    public ServiceRegistry getServices() {
    	return context.getServices();
    }
    
    public PluginLogger getLogger() {
    	return context.getLogger();
    }
    
    public Context getContext() {
    	return PluginFragmentHelper.getPluginContext(getPluginId());
    }
    
    public abstract void onInitialized(PluginContext context);
    
    public abstract String getPluginId();

    @Override
    public boolean activate() {
        return true;
    }

    @Override
    public boolean deactivate() {
        return true;
    }

    @Override
    public void dispose() {}

    @Nullable
    @Override
    public List<EditorTabItem> getMainEditorTabs() {
        return null;
    }

    @Nullable
    @Override
    public List<NavigationItem> getSideMenuItems() {
        return null;
    }


    @Override
    public void onEditorTabSelected(@NonNull String tabId, @NonNull Fragment fragment) {}

    @Override
    public void onEditorTabClosed(@NonNull String tabId) {}

    @Override
    public boolean canCloseEditorTab(@NonNull String tabId) {
        return true;
    }

    @Nullable
    @Override
    public List<MenuItem> getMainMenuItems() {
        return null;
    }

    @Nullable
    @Override
    public List<MenuItem> getContextMenuItems(@NonNull ContextMenuContext _context) {
        return null;
    }

    @Nullable
    @Override
    public List<TabItem> getEditorTabs() {
        return null;
    }

    @Nullable
    @Override
    public List<ToolbarAction> getToolbarActions() {
        return null;
    }

    @Nullable
    @Override
    public List<FabAction> getFabActions() {
        return null;
    }
    @NonNull
    @Override
    public Set<String> getHiddenToolbarActionIds() {
        return Collections.emptySet();
    }
}
