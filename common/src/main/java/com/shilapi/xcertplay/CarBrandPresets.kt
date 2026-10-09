package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

data class CarBrandPreset(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val defaultOemName: String,
    val assetFileName: String,
) {
    fun displayName(context: Context): String {
        val locale = androidx.core.os.ConfigurationCompat.getLocales(context.resources.configuration)[0]
        val isZh = locale?.language?.startsWith("zh") == true
        return if (isZh) "$nameZh ($nameEn)" else nameEn
    }

    fun loadBitmap(context: Context): Bitmap? = try {
        context.assets.open("car_brands/$assetFileName").use {
            BitmapFactory.decodeStream(it)
        }
    } catch (_: Exception) {
        null
    }

    fun loadBytes(context: Context): ByteArray? = try {
        context.assets.open("car_brands/$assetFileName").use {
            it.readBytes()
        }
    } catch (_: Exception) {
        null
    }
}

object CarBrandPresets {
    val ALL: List<CarBrandPreset> = listOf(
        CarBrandPreset("byd", "比亚迪", "BYD", "BYD", "byd.png"),
        CarBrandPreset("geely", "吉利", "Geely", "Geely", "geely.png"),
        CarBrandPreset("nissan", "日产", "Nissan", "Nissan", "nissan.png"),
        CarBrandPreset("toyota", "丰田", "Toyota", "Toyota", "toyota.png"),
        CarBrandPreset("honda", "本田", "Honda", "Honda", "honda.png"),
        CarBrandPreset("volkswagen", "大众", "Volkswagen", "VW", "volkswagen.png"),
        CarBrandPreset("buick", "别克", "Buick", "Buick", "buick.png"),
        CarBrandPreset("ford", "福特", "Ford", "Ford", "ford.png"),
        CarBrandPreset("chery", "奇瑞", "Chery", "Chery", "chery.png"),
        CarBrandPreset("changan", "长安", "Changan", "Changan", "changan.png"),
        CarBrandPreset("haval", "哈弗", "Haval", "Haval", "haval.png"),
        CarBrandPreset("hyundai", "现代", "Hyundai", "Hyundai", "hyundai.png"),
        CarBrandPreset("hongqi", "红旗", "Hongqi", "Hongqi", "hongqi.png"),
        CarBrandPreset("wuling", "五菱", "Wuling", "Wuling", "wuling.png"),
        CarBrandPreset("tesla", "特斯拉", "Tesla", "Tesla", "tesla.png"),
        CarBrandPreset("bmw", "宝马", "BMW", "BMW", "bmw.png"),
        CarBrandPreset("mercedes", "奔驰", "Mercedes-Benz", "Mercedes", "mercedes.png"),
        CarBrandPreset("audi", "奥迪", "Audi", "Audi", "audi.png"),
        CarBrandPreset("lexus", "雷克萨斯", "Lexus", "Lexus", "lexus.png"),
        CarBrandPreset("porsche", "保时捷", "Porsche", "Porsche", "porsche.png"),
        CarBrandPreset("volvo", "沃尔沃", "Volvo", "Volvo", "volvo.png"),
        CarBrandPreset("mazda", "马自达", "Mazda", "Mazda", "mazda.png"),
        CarBrandPreset("chevrolet", "雪佛兰", "Chevrolet", "Chevy", "chevrolet.png"),
        CarBrandPreset("kia", "起亚", "Kia", "Kia", "kia.png"),
    )
}
