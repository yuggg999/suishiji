package com.suishiji.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.suishiji.R
import com.suishiji.databinding.ItemTransactionBinding
import com.suishiji.db.Transaction
import java.text.SimpleDateFormat
import java.util.*

class TransactionAdapter(
    private val onAddressClick: ((Transaction) -> Unit)? = null,
    private val onEdit: ((Transaction) -> Unit)? = null,
    private val onSelectionChanged: ((Int, String?) -> Unit)? = null
) : RecyclerView.Adapter<TransactionAdapter.VH>() {

    private var items = mutableListOf<Transaction>()
    private val createdAtFormat = SimpleDateFormat("MM/dd HH:mm", Locale.CHINA)
    private val _selectedIds = mutableSetOf<Long>()
    val selectedIds: Set<Long> get() = _selectedIds
    var isBatchMode = false
    var batchType: String? = null

    fun setData(data: List<Transaction>) {
        items.clear()
        items.addAll(data)
        _selectedIds.clear()
        isBatchMode = false
        batchType = null
        notifyDataSetChanged()
    }

    fun removeAt(pos: Int) {
        val id = items[pos].id
        items.removeAt(pos)
        _selectedIds.remove(id)
        notifyItemRemoved(pos)
    }

    val currentList get() = items.toList()

    fun getSelectedItems(): List<Transaction> = items.filter { it.id in _selectedIds }

    fun clearSelection() {
        _selectedIds.clear()
        isBatchMode = false
        batchType = null
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0, null)
    }

    private fun toggleSelection(t: Transaction) {
        val id = t.id
        if (id in _selectedIds) {
            _selectedIds.remove(id)
        } else {
            if (batchType != null && t.type != batchType) return
            _selectedIds.add(id)
        }
        onSelectionChanged?.invoke(_selectedIds.size, batchType)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTransactionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.binding.tvCategory.text = t.category
        holder.binding.tvCreatedAt.text = createdAtFormat.format(Date(t.createdAt))
        holder.binding.tvAmount.text = if (t.type == "expense") "-¥%.2f".format(t.amount) else "+¥%.2f".format(t.amount)
        holder.binding.tvAmount.setTextColor(
            holder.itemView.context.getColor(if (t.type == "expense") R.color.expense else R.color.income)
        )
        if (!t.address.isNullOrBlank()) {
            holder.binding.tvAddress.text = t.address
            holder.binding.tvAddress.visibility = View.VISIBLE
            holder.binding.tvAddress.setOnClickListener { onAddressClick?.invoke(t) }
        } else {
            holder.binding.tvAddress.visibility = View.GONE
        }
        if (t.note.isNotBlank()) {
            holder.binding.tvNote.text = t.note
            holder.binding.tvNote.visibility = View.VISIBLE
        } else {
            holder.binding.tvNote.visibility = View.GONE
        }

        holder.binding.tvCreatedAt.text = createdAtFormat.format(Date(t.createdAt))

        val cb = holder.binding.cbSelect
        if (isBatchMode) {
            val isOpposite = batchType != null && t.type != batchType
            val isSelected = t.id in _selectedIds

            cb.visibility = View.VISIBLE
            cb.isChecked = isSelected

            val alpha = if (isOpposite) 0.3f else 1.0f
            holder.itemView.alpha = alpha

            if (isOpposite) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
            } else {
                holder.itemView.setOnClickListener {
                    toggleSelection(t)
                }
                holder.itemView.setOnLongClickListener(null)
            }
        } else {
            holder.itemView.alpha = 1.0f
            cb.visibility = View.GONE
            holder.itemView.setOnClickListener {
                onEdit?.invoke(t)
            }
            holder.itemView.setOnLongClickListener {
                isBatchMode = true
                batchType = t.type
                _selectedIds.add(t.id)
                notifyDataSetChanged()
                onSelectionChanged?.invoke(1, batchType)
                true
            }
        }
    }

    override fun getItemCount() = items.size

    class VH(val binding: ItemTransactionBinding) : RecyclerView.ViewHolder(binding.root)
}
