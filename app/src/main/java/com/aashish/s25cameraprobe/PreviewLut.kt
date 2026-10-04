package com.aashish.s25cameraprobe

import android.graphics.*
import java.io.InputStream

/** SDR preview-only 3D CUBE lookup. Camera/recorder surfaces are never changed. */
class PreviewLut(input:InputStream) {
    val size:Int
    private val bitmap:Bitmap
    init {
        var dimension=0
        var low=floatArrayOf(0f,0f,0f)
        var high=floatArrayOf(1f,1f,1f)
        val rows=mutableListOf<FloatArray>()
        input.bufferedReader().useLines { lines -> lines.forEach { line ->
            val parts=line.substringBefore('#').trim().split(Regex("\\s+"))
            when(parts.firstOrNull()) {
                "LUT_1D_SIZE" -> error("Only 3D .cube LUTs are supported")
                "LUT_3D_SIZE" -> { dimension=parts[1].toInt(); require(dimension in 2..65) { "LUT size must be 2–65" } }
                "DOMAIN_MIN" -> low=parts.drop(1).map { it.toFloat() }.toFloatArray()
                "DOMAIN_MAX" -> high=parts.drop(1).map { it.toFloat() }.toFloatArray()
                else -> if(parts.size==3 && parts[0].toFloatOrNull()!=null) { require(rows.size<65*65*65); rows.add(parts.map { it.toFloat().also { x -> require(x.isFinite()) } }.toFloatArray()) }
            }
        } }
        require(dimension>0 && rows.size==dimension*dimension*dimension) { "Incomplete 3D LUT" }
        require(low.contentEquals(floatArrayOf(0f,0f,0f)) && high.contentEquals(floatArrayOf(1f,1f,1f))) { "LUT domain must be 0–1" }
        size=dimension
        val pixels=IntArray(size*size*size)
        for(b in 0 until size) for(g in 0 until size) for(r in 0 until size) {
            val v=rows[(b*size+g)*size+r]
            pixels[g*size*size+b*size+r]=Color.rgb((v[0].coerceIn(0f,1f)*255).toInt(),(v[1].coerceIn(0f,1f)*255).toInt(),(v[2].coerceIn(0f,1f)*255).toInt())
        }
        bitmap=Bitmap.createBitmap(pixels,size*size,size,Bitmap.Config.ARGB_8888)
    }
    fun effect(strength:Float):RenderEffect {
        val shader=RuntimeShader("""
            uniform shader camera;
            uniform shader lut;
            uniform float size;
            uniform float strength;
            half4 main(float2 xy) {
                half4 pixel=camera.eval(xy);
                float3 c=clamp(float3(pixel.rgb)/max(float(pixel.a),0.0001),0.0,1.0);
                float z=c.b*(size-1.0);
                float b0=floor(z);
                float b1=min(b0+1.0,size-1.0);
                float2 p0=float2(b0*size+c.r*(size-1.0)+0.5,c.g*(size-1.0)+0.5);
                float2 p1=float2(b1*size+c.r*(size-1.0)+0.5,c.g*(size-1.0)+0.5);
                half3 graded=mix(lut.eval(p0).rgb,lut.eval(p1).rgb,half(z-b0));
                return half4(mix(pixel.rgb,graded*pixel.a,half(strength)),pixel.a);
            }
        """)
        val buffer=BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }
        shader.setInputBuffer("lut",buffer)
        shader.setFloatUniform("size",size.toFloat()); shader.setFloatUniform("strength",strength.coerceIn(0f,1f))
        return RenderEffect.createRuntimeShaderEffect(shader,"camera")
    }
}
