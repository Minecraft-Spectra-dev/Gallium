#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;

void main() {
    // Premultiplied RGB can contain additive glow even where the entity's alpha is zero.
    fragColor = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
}
