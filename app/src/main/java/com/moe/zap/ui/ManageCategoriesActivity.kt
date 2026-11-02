package com.moe.zap.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.moe.zap.R
import com.moe.zap.core.Kind
import com.moe.zap.data.Lock
import com.moe.zap.data.Repo

/** Press OK on a category to step it through Shown, Hidden and Locked. */
class ManageCategoriesActivity : Activity() {
    private class Row(val kind: Kind, val id: String, val name: String)

    private var rows: List<Row> = emptyList()
    private lateinit var grid: VerticalGridView
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)
        grid = findViewById(R.id.manageList)
        grid.tune()
        grid.setVerticalSpacing(dp(2))
        grid.adapter = adapter
        Repo.bg({
            val out = ArrayList<Row>()
            Repo.db.readableDatabase.rawQuery("SELECT kind, id, name FROM category ORDER BY kind, pos", null).use { c ->
                while (c.moveToNext()) out += Row(Kind.of(c.getInt(0)), c.getString(1), c.getString(2))
            }
            out as List<Row>
        }) { r ->
            rows = r.getOrDefault(emptyList())
            adapter.notifyDataSetChanged()
            grid.requestFocus()
        }
    }

    private fun label(state: Lock.State) = when (state) {
        Lock.State.VISIBLE -> "Shown"
        Lock.State.HIDDEN -> "Hidden"
        Lock.State.LOCKED -> "🔒 Locked"
    }

    private fun step(row: Row, position: Int) {
        val state = Lock.stateOf(row.kind, row.id)
        val next = when (state) {
            Lock.State.VISIBLE -> Lock.State.HIDDEN
            Lock.State.HIDDEN -> Lock.State.LOCKED
            Lock.State.LOCKED -> Lock.State.VISIBLE
        }
        val apply = {
            Lock.setState(row.kind, row.id, next)
            adapter.notifyItemChanged(position)
        }
        when {
            // Nobody can quietly unlock a category without the PIN.
            state == Lock.State.LOCKED -> PinDialog.verify(this, "Enter PIN to unlock") { apply() }
            next == Lock.State.LOCKED && !Lock.hasPin() -> PinDialog.create(this) { apply() }
            else -> apply()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text) {
            init {
                text.setOnClickListener {
                    val p = bindingAdapterPosition
                    rows.getOrNull(p)?.let { step(it, p) }
                }
            }
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView
        )

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = rows[position]
            val section = when (r.kind) {
                Kind.LIVE -> "Live"
                Kind.MOVIE -> "Movies"
                Kind.SERIES -> "Series"
            }
            h.text.text = "$section  ·  ${r.name}  —  ${label(Lock.stateOf(r.kind, r.id))}"
        }
    }
}
