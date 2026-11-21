package dev.omar.plugin.iconsrepo.ui.adapter;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import dev.omar.plugin.iconsrepo.data.models.IconModel;

public class IconModelDiffCallback extends DiffUtil.ItemCallback<IconModel> {

    @Override
    public boolean areItemsTheSame(@NonNull IconModel oldItem, @NonNull IconModel newItem) {
        return oldItem.getIconName().equals(newItem.getIconName());
    }

    @Override
    public boolean areContentsTheSame(IconModel oldItem, IconModel newItem) {
        return true;
    }

    @Override
    public Object getChangePayload(IconModel oldItem, IconModel newItem) {
        return null;
    }
}
