package dev.omar.plugin.iconsrepo.repository;

import android.content.Context;
import android.content.res.AssetManager;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.caverock.androidsvg.SVG;
import com.itsaky.androidide.plugins.base.PluginFragmentHelper;
import dev.omar.plugin.iconsrepo.Main;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dev.omar.plugin.iconsrepo.models.IconModel;
import dev.omar.plugin.iconsrepo.utils.Utils;

public class IconRepository {

    private Context context;
    private final String zipFileName = "material_icon.zip";
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private List<IconModel> iconsList;

    public IconRepository(Context context) {
        if (context == null) {
            this.context = PluginFragmentHelper.getPluginContext(Main.PLUGIN_ID);
        } else {
            this.context = context;
        }
        iconsList = new LinkedList<IconModel>();
    }

    public interface LoadIconsCallback {
        public void onLoadded(List<IconModel> list);

        public void onError(Throwable th);
    }

    public void shutdown() {
        executor.shutdownNow();
        if (iconsList != null) {
            iconsList.clear();
        }
        context = null;
    }

    public void loadAllIconsAsync(final LoadIconsCallback callback) {
        if (callback == null) return;
        if (executor.isShutdown()) return;
        executor.submit(
                () -> {
                    try {
                        final List<IconModel> list = loadAllIcons();
                        runOnUiThread(
                                () -> {
                                    callback.onLoadded(list);
                                });
                    } catch (Exception err) {
                        runOnUiThread(
                                () -> {
                                    callback.onError(err);
                                });
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

        try (InputStream inputStream = assets.open(zipFileName);
                ZipInputStream zipIn = new ZipInputStream(inputStream)) {

            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                // نتجاهل المجلدات والملفات التي ليست بصيغة SVG
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".svg")) {
                    continue;
                }

                // استخراج اسم الأيقونة بدون مسار وبدون امتداد
                String fullName = entry.getName(); // مثال: svg/home.svg
                String iconName = extractIconName(fullName);

                // قراءة بيانات SVG إلى مصفوفة بايتات
                byte[] iconData = Utils.readAllBytes(zipIn);

                // تجاهل الأيقونات الفارغة
                if (iconData != null && iconData.length > 0) {
                    try {
                        icons.add(new IconModel(iconName, iconData));
                    } catch (Exception err) {

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
}
