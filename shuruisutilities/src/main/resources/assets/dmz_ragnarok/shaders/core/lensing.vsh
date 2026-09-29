#version 150

in vec3 Position;
in vec2 UV0;

out vec2 texCoord0;

// Fullscreen pass: Position is already in NDC (-1..1), so no matrix transform. UV0 maps 0..1 across the grabbed frame.
void main() {
    gl_Position = vec4(Position, 1.0);
    texCoord0 = UV0;
}
