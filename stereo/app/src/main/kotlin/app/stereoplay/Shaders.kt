package app.stereoplay

internal object Shaders {
    const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val HEAD = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uVideo;
uniform mat4 uSt;
in vec2 vUv;
out vec4 oColor;
vec3 video(vec2 uv) { return texture(uVideo, (uSt * vec4(uv, 0.0, 1.0)).xy).rgb; }
"""

    /** The frame shrunk to the network's input size; four taps soften the aliasing. */
    const val SMALL = HEAD + """
uniform vec2 uTexel;
void main() {
    vec2 o = uTexel * 0.25;
    vec3 c = video(vUv + vec2(-o.x, -o.y)) + video(vUv + vec2(o.x, -o.y))
           + video(vUv + vec2(-o.x, o.y)) + video(vUv + vec2(o.x, o.y));
    oColor = vec4(c * 0.25, 1.0);
}
"""

    /**
     * Joint bilateral upsampling: the coarse depth map is brought up to video resolution,
     * taking from each neighbouring depth sample in proportion to how close its colour is to
     * this pixel's. Depth edges then snap to the edges in the picture instead of blurring.
     */
    const val UPSAMPLE = HEAD + """
uniform highp sampler2D uDepth;
uniform sampler2D uSmall;
uniform vec2 uLow;
uniform float uColorSigma;
void main() {
    vec3 g = video(vUv);
    vec2 pos = vUv * uLow - 0.5;
    vec2 base = floor(pos);
    vec2 f = pos - base;
    ivec2 hiC = ivec2(uLow) - 1;
    float wsum = 0.0;
    float dsum = 0.0;
    for (int j = -1; j <= 2; j++) {
        for (int i = -1; i <= 2; i++) {
            ivec2 c = clamp(ivec2(base) + ivec2(i, j), ivec2(0), hiC);
            vec2 dxy = vec2(float(i), float(j)) - f;
            float ws = exp(-dot(dxy, dxy) * 0.6);
            vec3 dc = texture(uSmall, (vec2(c) + 0.5) / uLow).rgb - g;
            float w = ws * exp(-dot(dc, dc) * uColorSigma) + 1e-4;
            wsum += w;
            dsum += w * texelFetch(uDepth, c, 0).r;
        }
    }
    oColor = vec4(dsum / wsum, 0.0, 0.0, 1.0);
}
"""

    /**
     * Depth-image-based rendering, done backwards: for each output pixel, find the source
     * pixel that lands on it once shifted by its own depth. Where several do, the nearest wins
     * (occlusion); where none does, the gap is filled from the farther side (background), not
     * by smearing the foreground.
     */
    const val OUTPUT = HEAD + """
uniform sampler2D uDepthFull;
uniform int uMode;      // 0 one eye, 1 anaglyph, 2 flat, 3 depth map
uniform float uEye;     // +1 left eye, -1 right eye
uniform float uHalf;    // half of the largest eye gap, in eye widths
uniform float uConv;    // depth that sits on the screen plane
uniform int uSteps;

float dep(float x, float y) { return texture(uDepthFull, vec2(x, y)).r; }

vec2 warp(vec2 uv, float e) {
    float h = uHalf;
    if (h <= 0.0) return uv;
    float stepX = 2.0 * h / float(uSteps - 1);
    float p0 = uv.x - h;
    float d0 = dep(p0, uv.y);
    float g0 = p0 + e * h * (d0 - uConv) - uv.x;
    float bestD = -1.0;
    float bestP = uv.x;
    float holeP = -1.0;
    for (int i = 1; i < uSteps; i++) {
        float p1 = p0 + stepX;
        float d1 = dep(p1, uv.y);
        float g1 = p1 + e * h * (d1 - uConv) - uv.x;
        if (g0 <= 0.0 && g1 > 0.0) {
            float p = mix(p0, p1, g0 / (g0 - g1));
            float d = dep(p, uv.y);
            float g = p + e * h * (d - uConv) - uv.x;
            if (abs(g) < stepX * 0.75) {
                p -= g;
                d = dep(p, uv.y);
                if (d > bestD) { bestD = d; bestP = p; }
            } else if (holeP < 0.0) {
                holeP = d0 < d1 ? p0 : p1;
            }
        }
        p0 = p1; d0 = d1; g0 = g1;
    }
    if (bestD >= 0.0) return vec2(bestP, uv.y);
    if (holeP >= 0.0) return vec2(holeP, uv.y);
    return vec2(uv.x - e * h * (dep(uv.x, uv.y) - uConv), uv.y);
}

// Dubois least-squares red/cyan matrices (columns).
const mat3 ML = mat3(0.437, -0.062, -0.048,  0.449, -0.062, -0.050,  0.164, -0.024, -0.017);
const mat3 MR = mat3(-0.011, 0.377, -0.026,  -0.032, 0.761, -0.093,  -0.007, 0.009, 1.234);

void main() {
    vec2 uv = vUv;
    if (uMode == 2) { oColor = vec4(video(uv), 1.0); return; }
    if (uMode == 3) { oColor = vec4(vec3(dep(uv.x, uv.y)), 1.0); return; }
    if (uMode == 1) {
        vec3 l = video(warp(uv, uEye));
        vec3 r = video(warp(uv, -uEye));
        oColor = vec4(clamp(ML * l + MR * r, 0.0, 1.0), 1.0);
        return;
    }
    oColor = vec4(video(warp(uv, uEye)), 1.0);
}
"""
}
