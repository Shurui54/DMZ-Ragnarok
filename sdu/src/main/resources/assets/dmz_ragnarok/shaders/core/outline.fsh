#version 150

uniform sampler2D Sampler0;
uniform vec4 PrimaryColor;
uniform vec4 SecondaryColor;
uniform vec2 TexelStep;
uniform float Time;
uniform float NoiseScale;
uniform float ColorMixSpeed;
uniform float NoiseIntensity;

in vec2 texCoord0;
out vec4 fragColor;

// DMZ transformation_unpack value-noise, used to modulate the primary<->secondary colour mix.
float hash(vec2 v) {
    return fract(sin(dot(v, vec2(127.1, 311.7))) * 43758.5453123);
}

float valueNoise(vec2 v) {
    vec2 i = floor(v);
    vec2 f = fract(v);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

// Colour a pixel only when it is just OUTSIDE the model silhouette (center empty, a neighbour within
// TexelStep covered) -> a clean edge. Its colour animates between primary and secondary per DMZ's math.
void main() {
    float center = texture(Sampler0, texCoord0).a;
    if (center > 0.3) {
        discard;
    }
    float m = 0.0;
    m = max(m, texture(Sampler0, texCoord0 + vec2( TexelStep.x, 0.0)).a);
    m = max(m, texture(Sampler0, texCoord0 + vec2(-TexelStep.x, 0.0)).a);
    m = max(m, texture(Sampler0, texCoord0 + vec2(0.0,  TexelStep.y)).a);
    m = max(m, texture(Sampler0, texCoord0 + vec2(0.0, -TexelStep.y)).a);
    m = max(m, texture(Sampler0, texCoord0 + vec2( TexelStep.x,  TexelStep.y)).a);
    m = max(m, texture(Sampler0, texCoord0 + vec2( TexelStep.x, -TexelStep.y)).a);
    m = max(m, texture(Sampler0, texCoord0 + vec2(-TexelStep.x,  TexelStep.y)).a);
    m = max(m, texture(Sampler0, texCoord0 + vec2(-TexelStep.x, -TexelStep.y)).a);
    if (m < 0.3) {
        discard;
    }

    vec2 noiseUv = texCoord0 * max(0.01, NoiseScale) + vec2(0.2, 0.15) * Time * 20.0;
    float noise = valueNoise(noiseUv);
    float pulse = 0.5 + 0.5 * sin(Time * 6.2831853 * max(0.01, ColorMixSpeed));
    float mixValue = clamp(pulse + (noise - 0.5) * max(0.0, NoiseIntensity) * 2.0, 0.0, 1.0);

    vec3 col = mix(PrimaryColor.rgb, SecondaryColor.rgb, mixValue);
    fragColor = vec4(col, PrimaryColor.a);
}
