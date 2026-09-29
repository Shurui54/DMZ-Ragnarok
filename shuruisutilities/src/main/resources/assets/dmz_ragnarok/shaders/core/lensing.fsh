#version 150

uniform sampler2D Sampler0;

// hole centre in 0..1 screen UV, its screen radius in the same aspect-corrected units, the framebuffer aspect ratio
// (width/height) so the warp stays circular on a widescreen, and the bend strength (tuned around 0.15..0.4).
uniform vec2 HoleScreenPos;
uniform float HoleRadius;
uniform float Aspect;
uniform float Strength;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec2 uv = texCoord0;
    vec2 c = HoleScreenPos;
    float a = Aspect;
    float R = HoleRadius;
    vec2 d = uv - c;
    d.x *= a;
    float r = length(d);
    float rN = r / R;
    float R_h = 0.55;
    if (rN < R_h) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    float OUTER = 3.0;
    float bend = Strength * R_h / (rN - R_h);
    float fade = clamp(1.0 - (rN - 1.0) / (OUTER - 1.0), 0.0, 1.0);
    bend *= fade * fade;
    vec2 dir = normalize(d);
    vec2 offset = dir * (-bend) * R;
    offset.x /= a;
    vec3 col = texture(Sampler0, uv + offset).rgb;
    fragColor = vec4(col, 1.0);
}
