package rs.zylos.novisad.ui.sheet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import rs.zylos.novisad.data.api.BuildingOrgListItem
import rs.zylos.novisad.databinding.ItemBuildingOrgBinding
import rs.zylos.novisad.ui.CategoryLabels

class OrgListAdapter(
    private val onClick: (BuildingOrgListItem) -> Unit,
) : RecyclerView.Adapter<OrgListAdapter.Holder>() {
    private var items: List<BuildingOrgListItem> = emptyList()

    fun submit(value: List<BuildingOrgListItem>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBuildingOrgBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemBuildingOrgBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BuildingOrgListItem) {
            binding.orgName.text = item.name
            val category = CategoryLabels.label(item.category_slug, item.category_name)
            val subtitle = when {
                !category.isNullOrBlank() && !item.floor.isNullOrBlank() -> "$category · ${item.floor}"
                !category.isNullOrBlank() -> category
                !item.floor.isNullOrBlank() -> item.floor
                else -> ""
            }
            binding.orgCategory.text = subtitle
            binding.orgCategory.visibility =
                if (subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
