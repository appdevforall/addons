package dev.omar.plugin.iconsrepo.utils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import dev.omar.plugin.iconsrepo.data.models.IconModel;

public class IconTrie {
    
    private static class Node {
        final ArrayMap<Character, Node> children = new ArrayMap<>();
        final ArrayList<IconModel> items = new ArrayList<>();
    }
    
    private Node root = new Node();
    
    public void clear() {
        root = new Node();
    }
    
    public void build(@NonNull List<IconModel> icons) {
        clear();
        for (IconModel icon : icons) {
            insert(icon);
        }
    }
    
    private void insert(@NonNull IconModel model) {
        String name = model.getIconName().toLowerCase(Locale.ROOT);
        Node current = root;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            Node child = current.children.get(c);
            if (child == null) {
                child = new Node();
                current.children.put(c, child);
            }
            child.items.add(model);
            current = child;
        }
    }
    
    public List<IconModel> searchPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>();
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        Node current = root;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            current = current.children.get(c);
            if (current == null) {
                return new ArrayList<>();
            }
        }
        return new ArrayList<>(current.items);
    }
}
