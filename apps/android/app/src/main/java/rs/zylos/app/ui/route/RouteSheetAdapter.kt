package rs.zylos.app.ui.route

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import rs.zylos.app.R
import rs.zylos.app.data.api.RouteItinerary
import rs.zylos.app.databinding.ItemRouteCardBinding
import rs.zylos.app.viewmodel.RouteLogic

class RouteSheetAdapter : RecyclerView.Adapter<RouteSheetAdapter.CardHolder>() {
    private var items: List<RouteItinerary> = emptyList()

    fun submit(value: List<RouteItinerary>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val binding = ItemRouteCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return CardHolder(binding)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class CardHolder(
        private val binding: ItemRouteCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(itinerary: RouteItinerary) {
            binding.routeCardSummary.text = RouteLogic.formatSummary(itinerary.duration_sec, itinerary.transfers)
            val legs = binding.routeCardLegs
            legs.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)
            itinerary.legs.forEach { leg ->
                val row = inflater.inflate(R.layout.item_route_leg, legs, false)
                val icon = row.findViewById<ImageView>(R.id.legIcon)
                val text = row.findViewById<TextView>(R.id.legText)
                val walk = leg.mode == "walk"
                icon.setImageResource(if (walk) R.drawable.ic_walk else R.drawable.ic_bus)
                icon.imageTintList = ContextCompat.getColorStateList(
                    binding.root.context,
                    if (walk) R.color.muted else R.color.accent,
                )
                text.text = RouteLogic.formatLeg(leg)
                legs.addView(row)
            }
        }
    }
}
