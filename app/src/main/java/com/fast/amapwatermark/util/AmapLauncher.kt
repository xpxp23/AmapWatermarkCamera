package com.fast.amapwatermark.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder
import java.util.Locale

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
        val cleanName = poiName.ifBlank { "水印相机实拍点" }
        val encodedName = URLEncoder.encode(cleanName, "UTF-8")
        val latStr = String.format(Locale.US, "%.6f", gcjLat)
        val lonStr = String.format(Locale.US, "%.6f", gcjLon)

        // dev=0 strictly specifies that lat/lon are already exact GCJ-02 coordinates (zero offset)
        val primaryUri = Uri.parse(
            "androidamap://viewMap?sourceApplication=FastWatermarkCam" +
                    "&poiname=$encodedName&lat=$latStr&lon=$lonStr&dev=0"
        )
        val secondaryUri = Uri.parse(
            "amapuri://viewMap?sourceApplication=FastWatermarkCam" +
                    "&poiname=$encodedName&lat=$latStr&lon=$lonStr&dev=0"
        )
        val webFallbackUrl = "https://uri.amap.com/marker?position=$lonStr,$latStr" +
                "&name=$encodedName&coordinate=gaode&callnative=1"

        launchWithFallbacks(context, primaryUri, secondaryUri, webFallbackUrl)
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
        val cleanName = poiName.ifBlank { "实拍目的地" }
        val encodedName = URLEncoder.encode(cleanName, "UTF-8")
        val latStr = String.format(Locale.US, "%.6f", gcjLat)
        val lonStr = String.format(Locale.US, "%.6f", gcjLon)

        val primaryUri = Uri.parse(
            "androidamap://route/plan/?sourceApplication=FastWatermarkCam" +
                    "&dlat=$latStr&dlon=$lonStr&dname=$encodedName&dev=0&t=0"
        )
        val secondaryUri = Uri.parse(
            "amapuri://route/plan/?sourceApplication=FastWatermarkCam" +
                    "&dlat=$latStr&dlon=$lonStr&dname=$encodedName&dev=0&t=0"
        )
        val webFallbackUrl = "https://uri.amap.com/navigation?to=$lonStr,$latStr,$encodedName" +
                "&mode=car&coordinate=gaode&callnative=1"

        launchWithFallbacks(context, primaryUri, secondaryUri, webFallbackUrl)
    }

    private fun launchWithFallbacks(
        context: Context,
        primaryUri: Uri,
        secondaryUri: Uri,
        webFallbackUrl: String
    ) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, primaryUri).apply {
                setPackage(AMAP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {}

        try {
            val intent2 = Intent(Intent.ACTION_VIEW, secondaryUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent2)
            return
        } catch (_: Exception) {}

        try {
            Toast.makeText(context, "未检测到高德App，正在打开高德精准网页地图...", Toast.LENGTH_SHORT).show()
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webFallbackUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "唤起地图失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
