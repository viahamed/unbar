package com.breakthebarriers.unbar.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

// (1.0.15, C7): the home-screen widget receiver. Declared in the
// manifest with the APPWIDGET_UPDATE filter and the
// unbar_widget_info provider XML.
class UnbarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = UnbarWidget()
}
