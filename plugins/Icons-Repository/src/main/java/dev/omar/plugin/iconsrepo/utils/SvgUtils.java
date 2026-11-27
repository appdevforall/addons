package dev.omar.plugin.iconsrepo.utils;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import org.jetbrains.annotations.Contract;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;


public class SvgUtils {

    public static final int SIZE_MULTIPLIER = 2;

    
    /**
     * Converts an SVG file to an Android Vector Drawable XML.
     * If fillColor is provided, it fills the SVG shapes with that color.
     */
    public void convert(String inputFilePath, String outputDir, @Nullable String fillColor) {
        try {
            Files.createDirectories(Paths.get(outputDir));
            String inputData = new String(Files.readAllBytes(Paths.get(inputFilePath)), StandardCharsets.UTF_8);

            if (fillColor != null) {
                String modifiedSvgPath = createModifiedSvg(inputFilePath, inputData, fillColor);
                convertToVector(modifiedSvgPath, outputDir, fillColor);
            } else {
                convertToVector(inputFilePath, outputDir, null);
            }
        } catch (Exception e) {
            Log.e("SvgUtils", "Error converting SVG", e);
        }
    }

    // ------ Private helper methods ------

    private String createModifiedSvg(String inputFilePath, String inputData, String fillColor) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);

        XmlPullParser inputParser = factory.newPullParser();
        inputParser.setInput(new StringReader(inputData));

        StringWriter writer = new StringWriter();
        XmlSerializer serializer = factory.newSerializer();
        serializer.setOutput(writer);

        serializer.startDocument("UTF-8", true);

        while (inputParser.getEventType() != XmlPullParser.END_DOCUMENT) {
            switch (inputParser.getEventType()) {
                case XmlPullParser.START_TAG: {
                    String tag = inputParser.getName();
                    serializer.startTag(inputParser.getNamespace(), tag);

                    // copy all attributes
                    for (int i = 0; i < inputParser.getAttributeCount(); i++) {
                        String attrName = inputParser.getAttributeName(i);
                        String attrValue = inputParser.getAttributeValue(i);

                        // replace fill color for shape elements
                        if ("fill".equals(attrName) && shouldApplyFillColor(tag)) {
                            serializer.attribute(null, attrName, fillColor);
                        } else {
                            serializer.attribute(null, attrName, attrValue);
                        }
                    }

                    // add fill attribute if none present
                    if (shouldApplyFillColor(tag) && !hasFillAttribute(inputParser)) {
                        serializer.attribute(null, "fill", fillColor);
                    }
                    break;
                }
                case XmlPullParser.END_TAG:
                    serializer.endTag(inputParser.getNamespace(), inputParser.getName());
                    break;
                case XmlPullParser.TEXT:
                    serializer.text(inputParser.getText());
                    break;
            }
            inputParser.next();
        }

        serializer.endDocument();

        Files.write(Paths.get(inputFilePath), writer.toString().getBytes(StandardCharsets.UTF_8));
        return inputFilePath;
    }

    private void convertToVector(String inputFilePath, String outputDir, @Nullable String fillColor) throws Exception {
        String data = new String(Files.readAllBytes(Paths.get(inputFilePath)), StandardCharsets.UTF_8);

        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);

        XmlPullParser inputParser = factory.newPullParser();
        inputParser.setInput(new StringReader(data));

        SvgAttributes svgAttributes = extractSvgAttributes(inputParser);

        StringWriter writer = new StringWriter();
        XmlSerializer serializer = factory.newSerializer();
        serializer.setOutput(writer);

        writeVectorDrawable(serializer, svgAttributes, inputParser, fillColor);

        String fileName = new File(inputFilePath).getName();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String outputFilePath = outputDir + "/" + baseName + ".xml";
        Files.write(Paths.get(outputFilePath), writer.toString().getBytes(StandardCharsets.UTF_8));

        Log.d("svgConverter", "Converted files saved to: " + outputDir);
    }

    private boolean shouldApplyFillColor(String tag) {
        return "path".equals(tag) || "circle".equals(tag) || "rect".equals(tag)
                || "ellipse".equals(tag) || "polygon".equals(tag) || "polyline".equals(tag);
    }

    private boolean hasFillAttribute(@NonNull XmlPullParser parser) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if ("fill".equals(parser.getAttributeName(i))) {
                return true;
            }
        }
        return false;
    }

    // SvgAttributes data class equivalent
    private static class SvgAttributes {
        final int width;
        final int height;
        final float viewportWidth;
        final float viewportHeight;

        SvgAttributes(int width, int height, float viewportWidth, float viewportHeight) {
            this.width = width;
            this.height = height;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
        }
    }

    @NonNull
    @Contract("_ -> new")
    private SvgAttributes extractSvgAttributes(@NonNull XmlPullParser parser) throws Exception {
        while (parser.getEventType() != XmlPullParser.START_TAG || !"svg".equals(parser.getName())) {
            parser.next();
        }

        String viewBox = parser.getAttributeValue(null, "viewBox");
        float viewportWidth = 24f;
        float viewportHeight = 24f;
        if (viewBox != null) {
            String[] parts = viewBox.split(" ");
            if (parts.length >= 4) {
                try {
                    viewportWidth = Float.parseFloat(parts[2]);
                } catch (NumberFormatException ignored) {}
                try {
                    viewportHeight = Float.parseFloat(parts[3]);
                } catch (NumberFormatException ignored) {}
            }
        }

        int width = 24;
        int height = 24;
        String widthAttr = parser.getAttributeValue(null, "width");
        String heightAttr = parser.getAttributeValue(null, "height");
        if (widthAttr != null) {
            widthAttr = widthAttr.replace("px", "");
            try {
                width = Integer.parseInt(widthAttr);
            } catch (NumberFormatException ignored) {}
        }
        if (heightAttr != null) {
            heightAttr = heightAttr.replace("px", "");
            try {
                height = Integer.parseInt(heightAttr);
            } catch (NumberFormatException ignored) {}
        }

        return new SvgAttributes(width, height, viewportWidth, viewportHeight);
    }

    private void writeVectorDrawable(@NonNull XmlSerializer serializer, @NonNull SvgAttributes attributes,
                                     @NonNull XmlPullParser parser, @Nullable String customFillColor) throws Exception {
        serializer.startDocument("UTF-8", true);

        serializer.startTag(null, "vector");
        serializer.attribute(null, "xmlns:android", "http://schemas.android.com/apk/res/android");
        serializer.attribute(null, "android:width", (attributes.width * SIZE_MULTIPLIER) + "dp");
        serializer.attribute(null, "android:height", (attributes.height * SIZE_MULTIPLIER) + "dp");
        serializer.attribute(null, "android:viewportWidth", String.valueOf(attributes.viewportWidth));
        serializer.attribute(null, "android:viewportHeight", String.valueOf(attributes.viewportHeight));

        int depth = 0;
        String groupFillColor = null;

        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            switch (parser.getEventType()) {
                case XmlPullParser.START_TAG:
                    depth++;
                    String tagName = parser.getName();
                    if ("g".equals(tagName)) {
                        groupFillColor = parser.getAttributeValue(null, "fill");
                    } else if ("path".equals(tagName)) {
                        writePath(serializer, parser, customFillColor != null ? customFillColor : groupFillColor);
                    } else if ("circle".equals(tagName) || "rect".equals(tagName) ||
                            "ellipse".equals(tagName) || "polygon".equals(tagName) || "polyline".equals(tagName)) {
                        String pathData = convertShapeToPath(parser);
                        writePathFromData(serializer, pathData, customFillColor != null ? customFillColor : groupFillColor);
                    }
                    break;

                case XmlPullParser.END_TAG:
                    depth--;
                    if ("g".equals(parser.getName())) {
                        groupFillColor = null;
                    }
                    break;
            }
            parser.next();
        }

        serializer.endTag(null, "vector");
        serializer.endDocument();
    }

    private void writePath(XmlSerializer serializer, @NonNull XmlPullParser parser,
                           @Nullable String inheritedFill) throws Exception {
        String pathData = parser.getAttributeValue(null, "d");
        String fill = parser.getAttributeValue(null, "fill");
        if (fill == null) {
            fill = inheritedFill;
        }
        if (fill == null) {
            fill = "#000000";
        }

        if (pathData != null) {
            serializer.startTag(null, "path");
            serializer.attribute(null, "android:pathData", pathData);
            serializer.attribute(null, "android:fillColor", fill);

            String opacityStr = parser.getAttributeValue(null, "opacity");
            if (opacityStr != null) {
                try {
                    float opacity = Float.parseFloat(opacityStr);
                    if (opacity < 1.0f) {
                        serializer.attribute(null, "android:fillAlpha", String.valueOf(opacity));
                    }
                } catch (NumberFormatException ignored) {}
            }

            serializer.endTag(null, "path");
        }
    }

    private void writePathFromData(@NonNull XmlSerializer serializer, String pathData,
                                   @Nullable String fill) throws Exception {
        serializer.startTag(null, "path");
        serializer.attribute(null, "android:pathData", pathData);
        serializer.attribute(null, "android:fillColor", fill != null ? fill : "#000000");
        serializer.endTag(null, "path");
    }

    private String convertShapeToPath(@NonNull XmlPullParser parser) {
        String tagName = parser.getName();
        switch (tagName) {
            case "circle":
                float cx = safeParseFloat(parser.getAttributeValue(null, "cx"));
                float cy = safeParseFloat(parser.getAttributeValue(null, "cy"));
                float r = safeParseFloat(parser.getAttributeValue(null, "r"));
                return createCirclePath(cx, cy, r);
            case "rect":
                float x = safeParseFloat(parser.getAttributeValue(null, "x"));
                float y = safeParseFloat(parser.getAttributeValue(null, "y"));
                float width = safeParseFloat(parser.getAttributeValue(null, "width"));
                float height = safeParseFloat(parser.getAttributeValue(null, "height"));
                float rx = safeParseFloat(parser.getAttributeValue(null, "rx"));
                float ry = safeParseFloat(parser.getAttributeValue(null, "ry"));
                return createRectPath(x, y, width, height, rx, ry);
            case "ellipse":
                float ecx = safeParseFloat(parser.getAttributeValue(null, "cx"));
                float ecy = safeParseFloat(parser.getAttributeValue(null, "cy"));
                float erx = safeParseFloat(parser.getAttributeValue(null, "rx"));
                float ery = safeParseFloat(parser.getAttributeValue(null, "ry"));
                return createEllipsePath(ecx, ecy, erx, ery);
            default:
                return "";
        }
    }

    private float safeParseFloat(String s) {
        if (s == null) return 0f;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    @NonNull
    @Contract(pure = true)
    private String createCirclePath(float cx, float cy, float r) {
        return "M " + (cx - r) + "," + cy +
                " A " + r + "," + r + " 0 0 1 " + (cx + r) + "," + cy +
                " A " + r + "," + r + " 0 0 1 " + (cx - r) + "," + cy + " Z";
    }

    @NonNull
    private String createRectPath(float x, float y, float width, float height,
                                  float rx, float ry) {
        if (rx <= 0f && ry <= 0f) {
            return "M " + x + "," + y + " h " + width + " v " + height + " h " + (-width) + " Z";
        } else {
            float effectiveRx = Math.min(rx, width / 2);
            float effectiveRy = Math.min(ry, height / 2);
            return "M " + (x + effectiveRx) + "," + y +
                    " h " + (width - 2 * effectiveRx) +
                    " a " + effectiveRx + "," + effectiveRy + " 0 0 1 " + effectiveRx + "," + effectiveRy +
                    " v " + (height - 2 * effectiveRy) +
                    " a " + effectiveRx + "," + effectiveRy + " 0 0 1 " + (-effectiveRx) + "," + effectiveRy +
                    " h " + (-(width - 2 * effectiveRx)) +
                    " a " + effectiveRx + "," + effectiveRy + " 0 0 1 " + (-effectiveRx) + "," + (-effectiveRy) +
                    " v " + (-(height - 2 * effectiveRy)) +
                    " a " + effectiveRx + "," + effectiveRy + " 0 0 1 " + effectiveRx + "," + (-effectiveRy) + " Z";
        }
    }

    @NonNull
    @Contract(pure = true)
    private String createEllipsePath(float cx, float cy, float rx, float ry) {
        return "M " + (cx - rx) + "," + cy +
                " A " + rx + "," + ry + " 0 0 1 " + (cx + rx) + "," + cy +
                " A " + rx + "," + ry + " 0 0 1 " + (cx - rx) + "," + cy + " Z";
    }
}