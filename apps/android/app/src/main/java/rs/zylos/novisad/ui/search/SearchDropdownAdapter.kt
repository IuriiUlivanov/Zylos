package rs.zylos.novisad.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import rs.zylos.novisad.R
import rs.zylos.novisad.data.api.SearchHit
import rs.zylos.novisad.data.api.SearchKind
import rs.zylos.novisad.data.local.SearchHistoryEntity
import rs.zylos.novisad.databinding.ItemSearchHitBinding
import rs.zylos.novisad.ui.CategoryLabels

sealed class SearchRow {
    data class Hit(val hit: SearchHit) : SearchRow()
    data class History(val item: SearchHistoryEntity) : SearchRow()
}

class SearchDropdownAdapter(
    private val onHit: (SearchHit) -> Unit,
    private val onHistory: (SearchHistoryEntity) -> Unit,
) : RecyclerView.Adapter<SearchDropdownAdapter.RowHolder>() {
    private var rows: List<SearchRow> = emptyList()

    fun submit(value: List<SearchRow>) {
        rows = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val binding = ItemSearchHitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RowHolder(binding)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(rows[position], showDivider = position < rows.lastIndex)
    }

    override fun getItemCount(): Int = rows.size

    inner class RowHolder(
        private val binding: ItemSearchHitBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SearchRow, showDivider: Boolean) {
            binding.hitDivider.visibility = if (showDivider) View.VISIBLE else View.GONE
            when (row) {
                is SearchRow.Hit -> bindHit(row.hit)
                is SearchRow.History -> bindHistory(row.item)
            }
        }

        private fun bindHit(hit: SearchHit) {
            val isOrg = hit.kind == SearchKind.organization
            bindKind(isOrg)
            binding.hitLabel.text = hit.label
            binding.hitSubtitle.text = if (isOrg) {
                CategoryLabels.label(hit.category_slug) ?: binding.root.context.getString(R.string.organizacija)
            } else {
                binding.root.context.getString(R.string.adresa)
            }
            binding.root.setOnClickListener { onHit(hit) }
        }

        private fun bindHistory(item: SearchHistoryEntity) {
            val isOrg = item.kind == SearchKind.organization.name
            bindKind(isOrg)
            binding.hitLabel.text = item.label?.takeIf { it.isNotBlank() } ?: item.query
            binding.hitSubtitle.text = if (isOrg) {
                CategoryLabels.label(item.categorySlug) ?: binding.root.context.getString(R.string.organizacija)
            } else {
                binding.root.context.getString(R.string.adresa)
            }
            binding.root.setOnClickListener { onHistory(item) }
        }

        private fun bindKind(isOrg: Boolean) {
            binding.kindBadge.setText(if (isOrg) R.string.kind_org else R.string.kind_adr)
            binding.kindBadge.setBackgroundResource(
                if (isOrg) R.drawable.bg_kind_org else R.drawable.bg_kind_adr,
            )
            binding.kindBadge.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (isOrg) R.color.accent else R.color.addr,
                ),
            )
        }
    }
}
