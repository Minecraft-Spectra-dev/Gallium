#version 450

out vec2 texCoord;

void main() {
    vec2 pos = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)) * 2.0 - 1.0;
    gl_Position = vec4(pos, 0.0, 1.0);
    texCoord = (pos + 1.0) * 0.5;
}
