package com.iamtungingit.wam.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iamtungingit.wam.R
import com.iamtungingit.wam.app.WamApp
import com.iamtungingit.wam.widget.WidgetStore
import com.iamtungingit.wam.widget.WapWidgetProvider
import com.iamtungingit.wam.widget.WidgetRenderer

/**
 * Lists all widget bindings + orphaned widget ids; allows removing bindings (spec §6.1).
 */
class WidgetManagerAdapter(
    private val records: List<com.iamtungingit.wam.widget.WidgetRecord>,
    private val orphanIds: List<Int>,
    private val onChanged: (Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private class RecordHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.wi_title)
        val status: TextView = v.findViewById(R.id.wi_status)
        val remove: ImageButton = v.findViewById(R.id.wi_remove)
    }

    private class OrphanHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.wi_title)
        val status: TextView = v.findViewById(R.id.wi_status)
        val remove: ImageButton = v.findViewById(R.id.wi_remove)
    }

    override fun getItemViewType(position: Int): Int =
        if (position < records.size) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_widget, parent, false)
        return if (viewType == 0) RecordHolder(v) else OrphanHolder(v)
    }

    override fun getItemCount(): Int = records.size + orphanIds.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        val app = WamApp.instance
        if (position < records.size) {
            val rec = records[position]
            val h = holder as RecordHolder
            val entry = app.registry.get(rec.wapId)
            h.title.text = "${entry?.name ?: rec.label.ifEmpty { rec.wapId }} · #${rec.widgetId}"
            h.status.text = when {
                entry == null -> ctx.getString(R.string.widget_status_removed)
                else -> "${ctx.getString(R.string.widget_status_ok)} · ${entry.name} v${entry.version}"
            }
            h.remove.setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.widget_remove_binding)
                    .setMessage("${rec.label} (#${rec.widgetId})")
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        WidgetStore(ctx).remove(rec.widgetId)
                        WidgetRenderer.renderFor(ctx, android.appwidget.AppWidgetManager.getInstance(ctx), rec.widgetId)
                        onChanged(false)
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        } else {
            val id = orphanIds[position - records.size]
            val h = holder as OrphanHolder
            h.title.text = "#$id"
            h.status.text = ctx.getString(R.string.widget_status_orphan)
            h.remove.setOnClickListener {
                // Orphaned ids have no record; removing from home screen is a user action.
                // We can only re-render the setup state.
                WidgetRenderer.renderFor(ctx, android.appwidget.AppWidgetManager.getInstance(ctx), id)
                onChanged(true)
            }
        }
    }
}
