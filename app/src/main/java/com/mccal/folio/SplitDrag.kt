package com.mccal.folio

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.os.UserHandle
import android.view.View

/**
 * Starts a system drag carrying an app, so dropping it at a screen edge opens it in split screen
 * (Android 12+). Idea credited to QuickLaunch (AhmedTheGeek); implemented independently.
 */
internal fun startSplitDrag(view: View, context: Context, component: ComponentName, user: UserHandle, label: String, icon: Bitmap?): Boolean =
    // Shortcuts aren't activities Folio can hand to split screen.
    if (component.className.startsWith(SHORTCUT_CLASS_PREFIX)) false else runCatching {
        // The activity-drag contract (MIME type + extras) isn't in the public SDK, so use its stable string
        // values; the PendingIntent is Folio's own, launching the app's main activity.
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val launch = android.app.PendingIntent.getActivity(context, component.hashCode(), launchIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val clip = ClipData(ClipDescription(label, arrayOf(MIME_APPLICATION_ACTIVITY)),
            ClipData.Item(Intent().putExtra(EXTRA_PENDING_INTENT, launch).putExtra(Intent.EXTRA_USER, user)))
        val size = (56 * context.resources.displayMetrics.density).toInt()
        val shadow = object : View.DragShadowBuilder(view) {
            override fun onProvideShadowMetrics(outSize: Point, outTouch: Point) { outSize.set(size, size); outTouch.set(size / 2, size / 2) }
            override fun onDrawShadow(canvas: Canvas) {
                icon?.let { canvas.drawBitmap(Bitmap.createScaledBitmap(it, size, size, true), 0f, 0f, null) } ?: super.onDrawShadow(canvas)
            }
        }
        view.startDragAndDrop(clip, shadow, null, View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_OPAQUE)
    }.getOrDefault(false)

private const val MIME_APPLICATION_ACTIVITY = "application/vnd.android.activity"
private const val EXTRA_PENDING_INTENT = "android.intent.extra.PENDING_INTENT"
