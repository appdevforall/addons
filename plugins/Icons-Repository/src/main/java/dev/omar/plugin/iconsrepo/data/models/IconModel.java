package dev.omar.plugin.iconsrepo.data.models;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

public class IconModel {
    
    private final String iconName;
    private final byte[] data;
    private SVG svgIcon;

    public IconModel(String iconName, byte[] data) {
        this.iconName = iconName;
        this.data = data;
    }

    public String getIconName() {
        return this.iconName;
    }
    
    public SVG getSvgIcon() throws SVGParseException {
        if (this.svgIcon == null) {
            ByteArrayInputStream in = new ByteArrayInputStream(getData());
            this.svgIcon = SVG.getFromInputStream(in);
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        return this.svgIcon;
    }
    
    public byte[] getData() {
        return this.data;
    }
    
    public InputStream newInputStream() {
        return new ByteArrayInputStream(getData());
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (iconName != null ? iconName.hashCode() : 0);
        result = 31 * result + (svgIcon != null ? svgIcon.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        IconModel other = (IconModel) obj;

        if (!Objects.equals(iconName, other.iconName)) return false;

        return Arrays.equals(data, other.data);
    }
}
