package dev.omar.plugin.iconsrepo.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.util.Config;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

public final class ImageUtils {

    public static Bitmap drawable2Bitmap(Drawable drawable) {
        Bitmap createBitmap;
        Canvas canvas;
        if (drawable instanceof BitmapDrawable bitmapDrawable) {
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }
        if (drawable.getIntrinsicWidth() > 0) {
            if (drawable.getIntrinsicHeight() > 0) {
                createBitmap =
                        Bitmap.createBitmap(
                                drawable.getIntrinsicWidth(),
                                drawable.getIntrinsicHeight(),
                                drawable.getOpacity() != -1
                                        ? Bitmap.Config.ARGB_8888
                                        : Bitmap.Config.RGB_565);
                canvas = new Canvas(createBitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
                return createBitmap;
            }
        }
        createBitmap =
                Bitmap.createBitmap(
                        1,
                        1,
                        drawable.getOpacity() != -1
                                ? Bitmap.Config.ARGB_8888
                                : Bitmap.Config.RGB_565);
        canvas = new Canvas(createBitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return createBitmap;
    }

    @NonNull
    public static Bitmap drawColor(@NonNull Bitmap bitmap, int color) {
        Paint paint = new Paint(1);
        paint.setColor(color);
        Bitmap createBitmap =
                Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        new Canvas(createBitmap).drawBitmap(bitmap.extractAlpha(), 0.0f, 0.0f, paint);
        return createBitmap;
    }
}
