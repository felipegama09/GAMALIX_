package br.gamaflix

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class CatAdapter(private val onPick: (index: Int, byClick: Boolean) -> Unit) :
    RecyclerView.Adapter<CatAdapter.VH>() {

    private var data: List<Cat> = emptyList()
    private var sel = 0
    private val focusHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun setData(d: List<Cat>, selected: Int) {
        data = d
        sel = selected
        notifyDataSetChanged()
    }

    fun setSelected(i: Int) {
        val old = sel
        sel = i
        notifyItemChanged(old, "sel")
        notifyItemChanged(i, "sel")
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cat, parent, false) as TextView)

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, i: Int) {
        h.tv.text = data[i].name
        h.tv.isSelected = i == sel
        h.tv.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onPick(p, true)
        }
        h.tv.setOnFocusChangeListener { _, focused ->
            val p = h.bindingAdapterPosition
            if (focused && p >= 0) {
                // Espera o foco parar: ao segurar a tecla não há atualização de lista a cada passo.
                focusHandler.removeCallbacksAndMessages(null)
                focusHandler.postDelayed({ onPick(p, false) }, 350)
            }
        }
    }

    override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) onBindViewHolder(h, i) else h.tv.isSelected = i == sel
    }
}

class ItemAdapter(private val onClick: (List<Item>, Int) -> Unit) :
    RecyclerView.Adapter<ItemAdapter.VH>() {

    var kind: Kind = Kind.LIVE
    private var data: List<Item> = emptyList()

    fun set(d: List<Item>) {
        data = d
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val title: TextView = v.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val item = data[i]
        val d = h.itemView.resources.displayMetrics.density
        val live = kind == Kind.LIVE
        h.title.text = item.name
        h.img.layoutParams.height = ((if (live) 78 else 178) * d).toInt()
        h.img.scaleType = if (live) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        val pad = if (live) (10 * d).toInt() else 0
        h.img.setPadding(pad, pad, pad, pad)

        val logo = item.logo
        if (logo.isNullOrBlank()) {
            h.img.dispose()
            h.img.setImageResource(R.drawable.ph)
        } else {
            h.img.load(logo) {
                size(260, 260)
                placeholder(R.drawable.ph)
                error(R.drawable.ph)
            }
        }
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onClick(data, p)
        }
    }
}
