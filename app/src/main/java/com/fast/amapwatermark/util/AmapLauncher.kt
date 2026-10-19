package com.fast.amapwatermark.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

object AmapLauncher {
    private const val AMAP_PACKAGE = "com.autonavi.minimap"

    /**
     * Launch Amap to place a pin at exact GCJ-02 coordinates with zero offset (dev=0)
     */
    fun jumpToAmapMarker(
        context: Context,
        gcjLat: Double,
        gcjLon: Double,
        poiName: String
    ) {
        val encodedName = URLEncoder.encode(poiName.ifBlank { "实拍水印位置" }, "UTF-8")
        // dev=0 tells Amap the incoming coordinates are already GCJ-02 (no extra offset!)
        val uriString = "androidamap://viewMap?sourceApplication=FastWatermarkCam" +
                "&poiname=$encodedName&lat=$gcjLat&lon=$gcjLon&dev=0"
        launchUriOrWebFallback(
            context = context,
            nativeUri = Uri.parse(uriString),
            webFallbackUrl = "https://uri.amap.com/marker?position=$gcjLon,$gcjLat&name=$encodedName&coordinate=gaode&callnative=1"
        )
    }

    /**
     * Launch Amap direct route planning to the exact GCJ-02 coordinates (dev=0)
     */
    fun jumpToAmapNavigation(
        context: Context,
        gcjLat: Double,
        gcjLon: Double,
        poiName: String
    ) {
        val encodedName = URLEncoder.encode(poiName.ifBlank { "实拍终点" }, "UTF-8")
        val uriString = "androidamap://route/plan/?sourceApplication=FastWatermarkCam" +
                "&dlat=$gcjLat&dlon=$gcjLon&dname=$encodedName&dev=0&t=0"
        launchUriOrWebFallback(
            context = context,
            nativeUri = Uri.parse(uriString),
            webFallbackUrl = "https://uri.amap.com/navigation?to=$gcjLon,$gcjLat,$encodedName&mode=car&coordinate=gaode&callnative=1"
        )
    }

    private fun launchUriOrWebFallback(context: Context, nativeUri: Uri, webFallbackUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, nativeUri).apply {
                setPackage(AMAP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to browser Amap URI if Amap app is not installed
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webFallbackUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
}
