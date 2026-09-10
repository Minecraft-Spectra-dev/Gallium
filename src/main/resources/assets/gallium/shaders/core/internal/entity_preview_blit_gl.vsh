#version 150

in vec3 Position;
uniform vec2 ScreenSize;
uniform vec4 ShaderAlign;
out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy / ScreenSize * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);
    texCoord = (Position.xy - ShaderAlign.xy) / ShaderAlign.zw;
    texCoord.y = 1.0 - texCoord.y;
}
