package dev.omar.plugin.iconsrepo.data.importer;

import dev.omar.plugin.iconsrepo.utils.Utils;

import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SvgToVectorImporter implements IconImporter {

    private static final Pattern PATH_D_PATTERN =
            Pattern.compile("<path[^>]*\\sd\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private static final String VECTOR_NAMESPACE = "http://schemas.android.com/apk/res/android";
    private static final String DEFAULT_WIDTH = "24dp";
    private static final String DEFAULT_HEIGHT = "24dp";
    private static final String DEFAULT_VIEWPORT_WIDTH = "24";
    private static final String DEFAULT_VIEWPORT_HEIGHT = "24";
    private static final String DEFAULT_FILL_COLOR = "#FF000000";

    @Override
    public ImportIconResult importIcon(ImportParams params) {
        try {
            String pathData = extractPathData(params.source.getStream());
            if (pathData == null || pathData.isEmpty()) {
                return ImportIconResult.error("No path data found in SVG");
            }

            // توليد XML بشكل عادي أولاً
            String rawXml = buildVectorXml(params, pathData);

            File destDir = params.dest.getDrawableDirectory(params.currentProject);
            String fileName = sanitizeFileName(params.source.getName()) + ".xml";
            File outputFile = new File(destDir, fileName);

            if (outputFile.exists()) {
                return ImportIconResult.error("File already exists: " + outputFile.getName());
            }

            Path parentPath = outputFile.getParentFile().toPath();
            Files.createDirectories(parentPath);
            Files.write(outputFile.toPath(), rawXml.getBytes(StandardCharsets.UTF_8));

            return ImportIconResult.success();
        } catch (Exception e) {
            return ImportIconResult.error(e.getMessage());
        }
    }

    private String extractPathData(InputStream svgStream) throws IOException {
        try (svgStream) {
            String content = new String(Utils.readAllBytes(svgStream), StandardCharsets.UTF_8);
            Matcher matcher = PATH_D_PATTERN.matcher(content);
            return matcher.find() ? matcher.group(1) : null;
        }
    }

    /** يبني نص XML خام (بدون تنسيق) متوافق مع Android VectorDrawable. */
    private String buildVectorXml(ImportParams params, String pathData) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlSerializer serializer = factory.newSerializer();

        StringWriter writer = new StringWriter();
        serializer.setOutput(writer);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startDocument("UTF-8", true);
        serializer.startTag(null, "vector");
        serializer.attribute(null, "xmlns:android", VECTOR_NAMESPACE);
        serializer.attribute(null, "android:width", DEFAULT_WIDTH);
        serializer.attribute(null, "android:height", DEFAULT_HEIGHT);
        serializer.attribute(null, "android:viewportWidth", DEFAULT_VIEWPORT_WIDTH);
        serializer.attribute(null, "android:viewportHeight", DEFAULT_VIEWPORT_HEIGHT);

        if (params.isUsedTint()) {
            serializer.attribute(null, "android:tint", params.tint);
        }

        serializer.startTag(null, "path");
        serializer.attribute(null, "android:fillColor", DEFAULT_FILL_COLOR);
        serializer.attribute(null, "android:pathData", pathData);
        serializer.endTag(null, "path");

        serializer.endTag(null, "vector");
        serializer.endDocument();
        serializer.flush();

        return writer.toString();
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
