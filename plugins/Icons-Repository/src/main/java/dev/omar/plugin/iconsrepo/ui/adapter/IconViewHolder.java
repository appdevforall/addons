package dev.omar.plugin.iconsrepo.ui.adapter;

import android.graphics.Bitmap;
import android.graphics.drawable.PictureDrawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textview.MaterialTextView;

import dev.omar.plugin.iconsrepo.R;
import dev.omar.plugin.iconsrepo.data.models.IconModel;
import dev.omar.plugin.iconsrepo.utils.ImageUtils;

public class IconViewHolder extends RecyclerView.ViewHolder {

    private AppCompatImageView icon;
    private MaterialTextView txt;
    private IconsAdapter adapter;

    private IconModel currentModel;

    public IconViewHolder(View view, IconsAdapter adapter) {
        super(view);
        this.adapter = adapter;
        icon = view.findViewById(R.id.iconImageView);
        txt = view.findViewById(R.id.iconNameTextView);
    }

    public void bindView(@NonNull final IconModel model) {
        currentModel = model;

        itemView.setOnClickListener(
                v -> {
                    if (adapter.itemClickListener != null) {
                        int position = getAdapterPosition();
                        if (position != RecyclerView.NO_POSITION) {
                            adapter.itemClickListener.onItemClicked(
                                    model, itemView, getAdapterPosition());
                        }
                    }
                });

        txt.setText(model.getIconName());

        icon.setImageResource(R.drawable.ic_category);

        Bitmap cacheIcon = adapter.getIconsCache().get(model.getIconName());
        if (cacheIcon != null) {
            icon.setImageBitmap(cacheIcon);
            return;
        }

        if (adapter.getRenderExecutor().isShutdown()) return;

        adapter.getRenderExecutor()
                .execute(
                        () -> {
                            try {
                                PictureDrawable pd =
                                        new PictureDrawable(model.getSvgIcon().renderToPicture());

                                Bitmap rawBitmap = ImageUtils.drawable2Bitmap(pd);
                                Bitmap coloredBitmap =
                                        ImageUtils.drawColor(
                                                rawBitmap,
                                                ContextCompat.getColor(
                                                        icon.getContext(),
                                                        R.color.m3_theme_Secondary));

                                adapter.getIconsCache().put(model.getIconName(), coloredBitmap);
                                adapter.getMainHandler()
                                        .post(
                                                () -> {
                                                    if (currentModel == model) {
                                                        icon.setImageBitmap(coloredBitmap);
                                                    }
                                                });
                                if (rawBitmap != coloredBitmap) {
                                    rawBitmap.recycle();
                                }

                            } catch (Exception err) {
                                adapter.getMainHandler()
                                        .post(
                                                () -> {
                                                    if (currentModel == model) {
                                                        icon.setImageResource(
                                                                R.drawable.ic_category);
                                                    }
                                                });
                            }
                        });
    }
}
