package dev.omar.plugin.iconsrepo.utils;
import android.content.ClipboardManager;
import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class Utils {
    
	private Utils() {
		
	}
    public static CharSequence getClipboardText(Context context) {
    	ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if(cm.hasPrimaryClip()){
            return cm.getPrimaryClip().getItemAt(0).getText();
        }
        return null;
    }

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

}