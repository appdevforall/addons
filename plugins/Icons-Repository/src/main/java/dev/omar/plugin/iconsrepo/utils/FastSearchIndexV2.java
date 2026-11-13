package dev.omar.plugin.iconsrepo.utils;

import java.util.*;

public class FastSearchIndexV2 {
    
        
    private static final class IntList {
        int[] data = new int[4];
        int size;
        
        void add(int v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }
    }
    
    
    private final List<String> allNames;
    private final String[] normalized;           // الأسماء مُجهّزة مسبقاً
    private final Map<String, IntList> prefixMap;  // بادئات الكلمات
    private final Map<String, IntList> wordMap;    // الكلمات الكاملة (البحث في أي مكان)
    private final Map<String, BitSet> cache;       // الكاش يخزن BitSet (أصغر بكثير)
    private static final int MAX_CACHE = 100;
    
    public FastSearchIndexV2(List<String> names) {
        this.allNames = new ArrayList<>(names);
        this.normalized = new String[names.size()];
        this.prefixMap = new HashMap<>();
        this.wordMap = new HashMap<>();
        this.cache = new LinkedHashMap<String, BitSet>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BitSet> eldest) {
                return size() > MAX_CACHE;
            }
        };
        buildIndex();
    }
    
    private void buildIndex() {
        for (int i = 0; i < allNames.size(); i++) {
            // تخزين النسخة المُحسّنة مرة واحدة فقط
            String norm = allNames.get(i).toLowerCase(Locale.ROOT).trim();
            normalized[i] = norm;
            
            // تقسيم ذكي: يفصل بالمسافات والنقاط والشرطات والشرطات السفلية
            // مثال: "ic_home_outline" → ["ic", "home", "outline"]
            String[] words = norm.split("[\\s._-]+");
            
            for (String word : words) {
                if (word.isEmpty()) continue;
                
                // فهرس الكلمات الكاملة (للبحث في أي مكان)
                addToMap(wordMap, word, i);
                
                // فهرس البادئات (للبحث الفوري بالبادئة)
                for (int len = 1; len <= word.length(); len++) {
                    addToMap(prefixMap, word.substring(0, len), i);
                }
            }
        }
    }
    
    private void addToMap(Map<String, IntList> map, String key, int idx) {
        IntList list = map.get(key);
        if (list == null) {
            list = new IntList();
            map.put(key, list);
        }
        list.add(idx);
    }
    
    // ========== 3. البحث المتقدم ==========
    public List<Integer> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return getAllIndices();
        }
        
        String q = query.toLowerCase(Locale.ROOT).trim();
        
        // التحقق من الكاش
        BitSet cached = cache.get(q);
        if (cached != null) {
            return bitSetToList(cached);
        }
        
        // تحليل الاستعلام: دعم - للاستبعاد
        String[] parts = q.split("\\s+");
        List<String> mustHave = new ArrayList<>();
        List<String> mustNot = new ArrayList<>();
        
        for (String part : parts) {
            if (part.startsWith("-") && part.length() > 1) {
                mustNot.add(part.substring(1)); // استبعاد
            } else {
                mustHave.add(part); // تضمين
            }
        }
        
        // ========== منطق AND للتضمين ==========
        BitSet result = null;
        
        for (String term : mustHave) {
            BitSet matches = findBitSet(term);
            if (matches == null) {
                return new ArrayList<>(); // كلمة مطلوبة غير موجودة
            }
            
            if (result == null) {
                result = (BitSet) matches.clone();
            } else {
                result.and(matches); // تقاطع (AND)
            }
            
            if (result.isEmpty()) {
                return new ArrayList<>(); // لا يوجد تقاطع
            }
        }
        
        // إذا لم يُدخل المستخدم كلمات تضمين، ابدأ بكل النتائج
        if (result == null) {
            result = new BitSet(allNames.size());
            result.set(0, allNames.size());
        }
        
        // ========== منطق NOT للاستبعاد ==========
        for (String term : mustNot) {
            BitSet matches = findBitSet(term);
            if (matches != null) {
                result.andNot(matches); // استبعاد
            }
        }
        
        // تخزين في الكاش وإرجاع
        cache.put(q, (BitSet) result.clone());
        return bitSetToList(result);
    }
    
    // ========== 4. البحث الهرمي (أسرع → أبطأ) ==========
    private BitSet findBitSet(String term) {
        // المرحلة 1: البحث في بادئات الكلمات (O(1) - فوري)
        IntList list = prefixMap.get(term);
        
        // المرحلة 2: البحث في الكلمات الكاملة (O(1))
        if (list == null) {
            list = wordMap.get(term);
        }
        
        // المرحلة 3: البحث في أي مكان (Contains - أبطأ لكن نادر)
        if (list == null) {
            list = containsSearch(term);
            if (list == null) return null;
        }
        
        // تحويل IntList إلى BitSet (سريع جداً)
        BitSet bs = new BitSet(allNames.size());
        for (int i = 0; i < list.size; i++) {
            bs.set(list.data[i]);
        }
        return bs;
    }
    
    // بحث Contains (أي مكان في الاسم) - نادر الاستخدام
    private IntList containsSearch(String term) {
        IntList result = new IntList();
        for (int i = 0; i < normalized.length; i++) {
            if (normalized[i].contains(term)) {
                result.add(i);
            }
        }
        return result.size > 0 ? result : null;
    }
    
    private List<Integer> getAllIndices() {
        List<Integer> all = new ArrayList<>(allNames.size());
        for (int i = 0; i < allNames.size(); i++) all.add(i);
        return all;
    }
    
    private List<Integer> bitSetToList(BitSet bs) {
        List<Integer> list = new ArrayList<>(bs.cardinality());
        for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
            list.add(i);
        }
        return list;
    }
}
