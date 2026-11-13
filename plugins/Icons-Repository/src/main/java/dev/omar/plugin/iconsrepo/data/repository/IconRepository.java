package dev.omar.plugin.iconsrepo.data.repository;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.itsaky.androidide.plugins.base.PluginFragmentHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dev.omar.plugin.iconsrepo.Main;
import dev.omar.plugin.iconsrepo.data.models.IconModel;

public class IconRepository {

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<IconModel> iconsList;

    public IconRepository(Context context) {
        if (context == null) {
            this.context = PluginFragmentHelper.getPluginContext(Main.PLUGIN_ID);
        } else {
            this.context = context;
        }
        iconsList = new LinkedList<>();
    }

    public interface LoadIconsCallback {
        void onLoaded(List<IconModel> list);

        void onError(Throwable th);
    }

    public void loadAllIconsAsync(final LoadIconsCallback callback) {
        if (callback == null) return;
        executor.submit(
                () -> {
                    try {
                        final List<IconModel> list = loadAllIcons();
                        runOnUiThread(
                                () -> callback.onLoaded(list));
                    } catch (Exception err) {
                        runOnUiThread(
                                () -> callback.onError(err));
                    }
                });
    }

    private void runOnUiThread(Runnable run) {
        if (run != null) {
            new Handler(Looper.getMainLooper()).post(run);
        }
    }

    @WorkerThread
    public List<IconModel> loadAllIcons() throws IOException {
        if (!iconsList.isEmpty()) return iconsList;
        List<IconModel> icons = new ArrayList<>();

        AssetManager assets = context.getAssets();

        String zipFileName = "material_icon.zip";
        try (InputStream inputStream = assets.open(zipFileName);
             ZipInputStream zipIn = new ZipInputStream(inputStream)) {

            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".svg")) {
                    continue;
                }

                String fullName = entry.getName();
                String iconName = extractIconName(fullName);

                byte[] iconData = readEntryBytes(zipIn);

                if (iconData.length > 0) {
                    try {
                        icons.add(new IconModel(iconName, iconData));
                    } catch (Exception ignored) {

                    }
                }

                zipIn.closeEntry();
            }
            iconsList.addAll(icons);
        }
        return icons;
    }

    @NonNull
    private String extractIconName(@NonNull String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }

    @NonNull
    private byte[] readEntryBytes(@NonNull ZipInputStream zipIn) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = zipIn.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }
}
