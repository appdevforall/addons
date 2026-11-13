package dev.omar.plugin.iconsrepo;


import androidx.annotation.NonNull;

import com.itsaky.androidide.plugins.PluginContext;
import com.itsaky.androidide.plugins.extensions.EditorTabItem;
import com.itsaky.androidide.plugins.extensions.NavigationItem;
import com.itsaky.androidide.plugins.services.IdeEditorTabService;

import dev.omar.plugin.iconsrepo.data.repository.IconRepository;
import dev.omar.plugin.iconsrepo.ui.fragments.MainFragment;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class Main extends BasePlugin {

    public static final String PLUGIN_ID = "dev.omar.plugin.iconsrepo";

    private IconRepository iconRepository;

    private static volatile Main instance;

    public static Main getInstance() {
        return instance;
    }

    @NonNull
    @Override
    public List<EditorTabItem> getMainEditorTabs() {
        return Collections.singletonList(
                new EditorTabItem(
                        PLUGIN_ID,
                        "Icons Repository",
                        R.drawable.ic_category,
                        MainFragment::new,
                        true,
                        false,
                        10,
                        true,
                        true,
                        "No description"));
    }

    @NonNull
    @Override
    public List<NavigationItem> getSideMenuItems() {
        return Collections.singletonList(
                new NavigationItem(
                        PLUGIN_ID,
                        "Icons Repository",
                        R.drawable.ic_category,
                        true,
                        true,
                        "No description",
                        10,"No tooltip",
                        () -> {
                            openFragment();
                            return null;
                        }));
    }

    private void openFragment() {
        IdeEditorTabService service = getServices().get(IdeEditorTabService.class);
        if (service == null) {
            getLogger().error("IdeEditorTabService is null.........!");
            return;
        }
        if (!service.isTabSystemAvailable()) {
            return;
        }
        try {
            if (!service.selectPluginTab(PLUGIN_ID)) {
                getLogger().warn("Can not select plugin tab........!");
            }
        } catch (Exception err) {
            getLogger().error("selectPluginTab", err);
        }
    }

    @Override
    public void onInitialized(PluginContext context) {
        instance = this;
        
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    public IconRepository getIconRepository() {
        if(iconRepository == null) {
        	iconRepository = new IconRepository(getContext());
        }
        return iconRepository;
    }


}
