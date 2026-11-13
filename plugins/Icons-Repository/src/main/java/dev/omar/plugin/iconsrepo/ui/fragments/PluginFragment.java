package dev.omar.plugin.iconsrepo.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import dev.omar.plugin.iconsrepo.Main;
import com.itsaky.androidide.plugins.base.PluginFragmentHelper;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

public class PluginFragment extends Fragment {

    @NonNull
    @Override
    public LayoutInflater onGetLayoutInflater(Bundle savedInstanceState) {
        var inflater = super.onGetLayoutInflater(savedInstanceState);
        return PluginFragmentHelper.getPluginInflater(Main.PLUGIN_ID, inflater);
    }
}
