package dev.omar.plugin.iconsrepo.ui.adapter;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import dev.omar.plugin.iconsrepo.utils.FastSearchIndexV2;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.omar.plugin.iconsrepo.R;
import dev.omar.plugin.iconsrepo.data.models.IconModel;
import dev.omar.plugin.iconsrepo.utils.FastSearchIndex;

public class IconsAdapter extends ListAdapter<IconModel, IconViewHolder>
        implements Filterable, TextWatcher {

    private List<IconModel> originalList;
    private FastSearchIndexV2 searchIndex;
    private final ExecutorService searchExecutor;
    private final Handler mainHandler;
    OnItemClickListener itemClickListener;
    private String currentQuery = "";

    private Runnable pendingSearch;
    private static final int DEBOUNCE_DELAY = 164; // milliseconds

    public IconsAdapter() {
        super(new IconModelDiffCallback());
        originalList = new ArrayList<>();
        searchExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setOriginalList(List<IconModel> fullList) {
        this.originalList = new ArrayList<>(fullList);
        searchExecutor.execute(
                () -> {
                    List<String> names = new ArrayList<>();
                    for (IconModel model : fullList) {
                        names.add(model.getIconName());
                    }
                    searchIndex = new FastSearchIndexV2(names);
                });
        submitList(fullList);
    }

    public interface OnItemClickListener {
        void onItemClicked(IconModel model, View view, int position);
    }

    @NonNull
    @Override
    public IconViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int id) {
        return new IconViewHolder(
                LayoutInflater.from(parent.getContext()).inflate(R.layout.item_icon, parent, false),
                this);
    }

    @Override
    public void onBindViewHolder(@NonNull IconViewHolder holder, int position) {
        holder.bindView(getItem(position));
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence keyword) {
                FilterResults results = new FilterResults();
                currentQuery = keyword == null ? "" : keyword.toString();

                if (searchIndex == null) {
                    results.values = new ArrayList<>(originalList);
                    return results;
                }

                List<Integer> indices = searchIndex.search(currentQuery);
                List<IconModel> filtered = new ArrayList<>(indices.size());
                for (int index : indices) {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (index < originalList.size()) {
                        filtered.add(originalList.get(index));
                    }
                }

                results.values = filtered;
                return results;
            }

            @SuppressLint("NotifyDataSetChanged")
            @Override
            protected void publishResults(CharSequence keyword, FilterResults results) {
                @SuppressWarnings("unchecked")
                List<IconModel> filteredList = (List<IconModel>) results.values;
                submitList(filteredList, () -> notifyDataSetChanged());
            }
        };
    }

    @SuppressLint("NotifyDataSetChanged")
    public void search(String keyword) {
        if (pendingSearch != null) {
            mainHandler.removeCallbacks(pendingSearch);
        }
        if (keyword == null || keyword.length() == 0) {
            submitList(originalList, () -> notifyDataSetChanged());
            return;
        }

        pendingSearch = () -> getFilter().filter(keyword);
        mainHandler.postDelayed(pendingSearch, DEBOUNCE_DELAY);
    }

    public void setOnItemClickListener(OnItemClickListener itemClickListener) {
        this.itemClickListener = itemClickListener;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        searchExecutor.shutdown();
    }

    @Override
    public void beforeTextChanged(CharSequence text, int start, int end, int len) {}

    @Override
    public void onTextChanged(CharSequence text, int start, int end, int len) {}

    @Override
    public void afterTextChanged(Editable editable) {
        search(editable.toString());
    }
}
