package dev.omar.plugin.iconsrepo.data.importer;

import dev.omar.plugin.iconsrepo.data.importer.IconSource;
import dev.omar.plugin.iconsrepo.data.models.IconModel;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;

public class AssetIconSource implements IconSource {

    private final String name;
    private final byte[] data;

    public AssetIconSource(String name, byte[] data) {
        this.name = name;
        this.data = data;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public InputStream getStream() throws IOException {
        return new ByteArrayInputStream(data);
    }
}
