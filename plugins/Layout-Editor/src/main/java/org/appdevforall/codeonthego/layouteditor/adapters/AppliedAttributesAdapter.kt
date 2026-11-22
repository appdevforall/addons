package org.appdevforall.codeonthego.layouteditor.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.appdevforall.codeonthego.layouteditor.LayoutEditorDocs
import org.appdevforall.codeonthego.layouteditor.databinding.ShowAttributeItemBinding
import org.appdevforall.codeonthego.layouteditor.utils.Constants

class AppliedAttributesAdapter(
    private val attrs: List<HashMap<String, Any>>,
    private val values: List<String>
) : RecyclerView.Adapter<AppliedAttributesAdapter.VH>() {

    var onClick: (Int) -> Unit = {}
    var onRemoveButtonClick: (Int) -> Unit = {}

    class VH(var binding: ShowAttributeItemBinding) : RecyclerView.ViewHolder(
        binding.root
    ) {
        var btnRemove = binding.btnRemoveAttribute
        var attributeName = binding.attributeName
        var attributeValue = binding.attributeValue
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(
            ShowAttributeItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val attributeName = attrs[position]["name"].toString()
        holder.attributeName.text = attributeName
        holder.attributeValue.text = values[position]

        LayoutEditorDocs.bindLongPress(holder.btnRemove, LayoutEditorDocs.TAG_REMOVE_ATTRIBUTE)

        if (attrs[position].containsKey(Constants.KEY_CAN_DELETE)) {
            holder.btnRemove.visibility = View.GONE
        }

        holder.binding.root.apply {
            setOnClickListener { onClick(position) }
            setOnLongClickListener {
                LayoutEditorDocs.showAttribute(it, attributeName)
                true
            }
        }
        holder.btnRemove.setOnClickListener { onRemoveButtonClick(position) }
    }

    override fun getItemCount(): Int {
        return attrs.size
    }
}
