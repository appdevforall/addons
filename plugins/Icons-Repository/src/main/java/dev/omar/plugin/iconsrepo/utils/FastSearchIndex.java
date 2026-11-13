package dev.omar.plugin.iconsrepo.utils;

import java.util.*;

public class FastSearchIndex {
    private final Map<String, List<Integer>> prefixIndex;
    private final List<String> allNames;
    private final Map<String, List<Integer>> searchCache;
    private static final int MAX_CACHE_SIZE = 50;

    public FastSearchIndex(List<String> names) {
        this.allNames = new ArrayList<>(names);
        this.prefixIndex = new HashMap<>();
        this.searchCache =
                new LinkedHashMap<String, List<Integer>>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, List<Integer>> eldest) {
                        return size() > MAX_CACHE_SIZE;
                    }
                };
        buildPrefixIndex();
    }

    private void buildPrefixIndex() {
        for (int i = 0; i < allNames.size(); i++) {
            String name = allNames.get(i).toLowerCase();
            for (int j = 1; j <= name.length(); j++) {
                String prefix = name.substring(0, j);
                prefixIndex.computeIfAbsent(prefix, k -> new ArrayList<>()).add(i);
            }
        }
    }

    public List<Integer> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return getAllIndices();
        }

        String normalizedQuery = query.toLowerCase().trim();

        if (searchCache.containsKey(normalizedQuery)) {
            return new ArrayList<>(Objects.requireNonNull(searchCache.get(normalizedQuery)));
        }

        Set<Integer> resultSet = new HashSet<>();
        String[] terms = normalizedQuery.split("\\s+");

        for (String term : terms) {
            List<Integer> matches = prefixIndex.get(term);
            if (matches != null) {
                if (resultSet.isEmpty()) {
                    resultSet.addAll(matches);
                } else {
                    resultSet.retainAll(matches);
                }
            }
        }

        List<Integer> result = new ArrayList<>(resultSet);
        Collections.sort(result);
        searchCache.put(normalizedQuery, result);
        return result;
    }

    private List<Integer> getAllIndices() {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < allNames.size(); i++) {
            all.add(i);
        }
        return all;
    }
}
