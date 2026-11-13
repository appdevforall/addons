package dev.omar.plugin.iconsrepo.ui.adapter;

import android.graphics.Bitmap;
import android.graphics.drawable.PictureDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.collection.LruCache;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textview.MaterialTextView;

import dev.omar.plugin.iconsrepo.R;
import dev.omar.plugin.iconsrepo.data.models.IconModel;
import dev.omar.plugin.iconsrepo.utils.ImageUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IconViewHolder extends RecyclerView.ViewHolder {

    private AppCompatImageView icon;
    private MaterialTextView txt;
    private IconsAdapter adapter;
    private static ExecutorService renderExecutor;
    private static Handler mainHandler;

    private IconModel currentModel;

    private static final LruCache<String, Bitmap> iconsCache =
            new LruCache<String, Bitmap>(8 * 1024) {
                @Override
                protected int sizeOf(String keyword, @NonNull Bitmap bitmap) {
                    return bitmap.getByteCount() / 1024;
                }
            };

    public IconViewHolder(View view, IconsAdapter adapter) {
        super(view);
        this.adapter = adapter;
        icon = view.findViewById(R.id.iconImageView);
        txt = view.findViewById(R.id.iconNameTextView);

        if (renderExecutor == null) {
            renderExecutor = Executors.newFixedThreadPool(4); // 4 threads للـSVG rendering
            mainHandler = new Handler(Looper.getMainLooper());
        }
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

        Bitmap cacheIcon = iconsCache.get(model.getIconName());
        if (cacheIcon != null) {
            icon.setImageBitmap(cacheIcon);
            return;
        }

        renderExecutor.execute(
                () -> {
                    try {
                        PictureDrawable pd =
                                new PictureDrawable(model.getSvgIcon().renderToPicture());

                        Bitmap rawBitmap = ImageUtils.drawable2Bitmap(pd);
                        Bitmap coloredBitmap =
                                ImageUtils.drawColor(
                                        rawBitmap,
                                        ContextCompat.getColor(
                                                icon.getContext(), R.color.m3_theme_Secondary));

                        iconsCache.put(model.getIconName(), coloredBitmap);
                        mainHandler.post(
                                () -> {
                                    if (currentModel == model) {
                                        icon.setImageBitmap(coloredBitmap);
                                    }
                                });
                        if (rawBitmap != coloredBitmap) {
                            rawBitmap.recycle();
                        }
                       
                    } catch (Exception err) {
                        mainHandler.post(
                                () -> {
                                    if (currentModel == model) {
                                        icon.setImageResource(R.drawable.ic_category);
                                    }
                                });
                    }
                });
    }
}
