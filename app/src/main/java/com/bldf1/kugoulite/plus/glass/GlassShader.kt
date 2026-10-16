package com.bldf1.kugoulite.plus.glass

/**
 * 液态玻璃 AGSL 着色器源码（Round 28）
 *
 * 逐字移植自 **QmDeve/AndroidLiquidGlassView**（MIT License，Copyright (c) 2025 QmDeve，
 * 贡献者 Donny Yale / Ahmed Sbai，https://github.com/QmDeve/AndroidLiquidGlassView）
 * 的 `core/src/main/res/raw/liquidglass_effect.agsl`。
 *
 * ⚠️ **为什么内置成字符串而不是放 `res/raw/`**：原库用 `target.getResources()`
 * 读 `R.raw.liquidglass_effect`，在本项目里 `target` 是**酷狗 Activity 的视图**
 * ⇒ 拿到的是**宿主的资源表**，模块自己的资源 ID 在里面根本不存在，
 * `openRawResource` 必然抛 `Resources.NotFoundException`。
 * 内置成常量彻底绕开资源表、也不用去搞 `ModuleResources`。
 *
 * 着色器语义（`content` 是录屏进来的背景）：
 *  · `-sd >= refractionHeight`（远离边缘的内部）⇒ 直接取背景 + 彩度/白点/对比度/染色
 *  · 边缘带内 ⇒ 按圆角矩形 SDF 梯度做**折射位移**，再叠 7 次采样做**色散**
 *  · 圆角之外 ⇒ 同样走折射分支（靠宿主 View 的 `clipToOutline` 裁成胶囊）
 */
internal object GlassShader {

    val SOURCE: String = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

uniform float contrast;
uniform float whitePoint;
uniform float chromaMultiplier;

uniform float3 tintColor;
uniform float tintAlpha;

const half3 rgbToY = half3(0.2126, 0.7152, 0.0722);

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float safeSign(float value) {
    return value < 0.0 ? -1.0 : 1.0;
}

float2 safeNormalize(float2 value, float2 fallback) {
    float len = length(value);
    if (len > 0.001) {
        return value / len;
    }
    return fallback;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        float2 outside = max(cornerCoord, 0.0);
        float outsideLength = length(outside);
        if (outsideLength > 0.001) {
            return sign(coord) * (outside / outsideLength);
        }
        float useX = step(cornerCoord.y, cornerCoord.x);
        return float2(
            useX * safeSign(coord.x),
            (1.0 - useX) * safeSign(coord.y)
        );
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 saturateColor(half4 color, float amount) {
    half3 lin = toLinearSrgb(color.rgb);
    float y = dot(lin, rgbToY);
    half3 gray = half3(y);
    half3 sat = fromLinearSrgb(mix(gray, lin, amount));
    return half4(sat, color.a);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        half4 baseColor = content.eval(coord);

        baseColor = saturateColor(baseColor, chromaMultiplier);
        float3 target = (whitePoint > 0.0) ? float3(1.0) : float3(0.0);
        baseColor.rgb = mix(baseColor.rgb, target, abs(whitePoint));
        baseColor.rgb = (baseColor.rgb - 0.5) * (1.0 + contrast) + 0.5;

        half3 tintedRGB = mix(baseColor.rgb, tintColor, tintAlpha);
        return half4(tintedRGB, baseColor.a);
    }

    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float smoothRadius = max(radius * 1.5, 30.0);
    float gradRadius = min(smoothRadius, min(halfSize.x, halfSize.y));

    float2 shapeGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 depthGrad = safeNormalize(centeredCoord, shapeGrad);
    float2 grad = safeNormalize(shapeGrad + depthEffect * depthGrad, shapeGrad);

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    color = saturateColor(color, chromaMultiplier);
    float3 target = (whitePoint > 0.0) ? float3(1.0) : float3(0.0);
    color.rgb = mix(color.rgb, target, abs(whitePoint));
    color.rgb = (color.rgb - 0.5) * (1.0 + contrast) + 0.5;

    half3 tintedRGB = mix(color.rgb, tintColor, tintAlpha);
    return half4(tintedRGB, color.a);
}
"""
}
